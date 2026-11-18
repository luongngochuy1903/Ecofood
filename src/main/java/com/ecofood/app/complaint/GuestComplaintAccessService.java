package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.VerifiedGuestAccess;
import com.ecofood.app.complaint.ComplaintCoreContracts.CommandResult;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service @RequiredArgsConstructor
public class GuestComplaintAccessService {
  private final ComplaintMongoStore store;
  private final ComplaintAccessSecrets secrets;
  private final Clock clock;
  @Value("${ecofood.complaints.access-ttl:P7D}") private Duration ttl;
  @Value("${ecofood.complaints.public-base-url:http://customerportal.ecofood.localhost:5173/complaint}") private String baseUrl;
  public record Issued(String trackingUrl, Instant expiresAt) {}
  public Issued ensure(CommandResult result, String receiptAccessId, String requestId) {
    if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofDays(30)) > 0) throw new IllegalStateException("Invalid Complaint access TTL");
    String token = secrets.derive("tracking", result.complaintId(), receiptAccessId, requestId);
    var expected = new GuestComplaintAccess(result.complaintId(), receiptAccessId, hash(token), result.processedAt(), result.processedAt().plus(ttl), null);
    var mongo = store.template(); var existing = mongo.findById(result.complaintId(), GuestComplaintAccess.class);
    if (existing == null) {
      try { mongo.insert(expected); existing = expected; }
      catch (DuplicateKeyException ex) { existing = mongo.findById(result.complaintId(), GuestComplaintAccess.class); if (existing == null) throw ex; }
    }
    if (!existing.receiptAccessId().equals(receiptAccessId) || !constant(existing.tokenHash(), expected.tokenHash())
        || existing.revokedAt() != null || !existing.expiresAt().isAfter(clock.instant())) throw unavailable();
    URI base;
    try {
      base = URI.create(baseUrl);
      if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null || !"/complaint".equals(base.getPath()) || base.getUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null) throw new IllegalArgumentException();
    } catch (Exception ex) { throw new IllegalStateException("Complaint public URL unavailable"); }
    // Fragment is never included in HTTP request targets/referrers; frontend uses a separate public POST client.
    return new Issued(base + "#proof=" + result.complaintId() + "." + token, existing.expiresAt());
  }
  public VerifiedGuestAccess verify(String complaintId, String token) {
    if (complaintId == null || !complaintId.matches("[a-f0-9]{24}") || token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
    var access = store.template().findById(complaintId, GuestComplaintAccess.class);
    if (access == null || access.revokedAt() != null || !access.expiresAt().isAfter(clock.instant()) || !constant(access.tokenHash(), hash(token))) throw unavailable();
    return new VerifiedGuestAccess(complaintId, access.expiresAt());
  }
  /** Trusted operation for future management integration; no public revoke endpoint. */
  public void revoke(String id) { ComplaintValidation.id(id); store.template().updateFirst(Query.query(Criteria.where("_id").is(id).and("revokedAt").is(null)), new Update().set("revokedAt", clock.instant()), GuestComplaintAccess.class); }
  private String hash(String token) { return ComplaintCommandIdentity.hash("guest-complaint-access:v1", token); }
  private boolean constant(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
  private ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Complaint access unavailable"); }
}
