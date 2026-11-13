package com.ecofood.app.receipt.service;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.receipt.config.ReceiptProperties;
import com.ecofood.app.receipt.dto.*;
import com.ecofood.app.receipt.entity.ReceiptAccess;
import com.ecofood.app.receipt.repository.ReceiptAccessRepository;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.core.dto.SaleOrchestratorConfig;
import com.ecofood.core.enums.TransactionStatus;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.SaleOrchestratorConfigProvider;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ReceiptService {
  private final InvoiceRepository invoices;
  private final ReceiptAccessRepository receipts;
  private final ObjectProvider<SaleOrchestratorConfigProvider> providers;
  private final ReceiptTokenCodec tokens;
  private final ReceiptProperties properties;
  private final Clock clock;

  public IssueReceiptResponse issue(String invoiceId, IssueReceiptRequest request) {
    PosIdentity pos = currentPos("RECEIPT_ISSUE");
    Invoice invoice = posInvoice(invoiceId, pos);
    invoiceId = invoice.getId();
    if (invoice.getStatus() != TransactionStatus.ACCEPTED)
      throw new IllegalStateException("Only ACCEPTED invoices can issue receipts");
    if (request == null || request.requestId() == null) throw new BadRequestException("requestId is required");
    String baseUrl = publicBaseUrl();
    Duration ttl = properties.getTtl();
    if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0 || ttl.compareTo(Duration.ofDays(30)) > 0)
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Receipt TTL must be between 1 second and 30 days");
    String id = tokens.receiptId(pos.tenantId(), pos.posId(), invoiceId, request.requestId().toString());
    String token = tokens.token(id);
    ReceiptAccess access = receipts.findByIdAndInvoiceIdAndTenantIdAndPosId(id, invoiceId, pos.tenantId(), pos.posId())
        .orElse(null);
    if (access == null) {
      access = new ReceiptAccess();
      access.setId(id); access.setTokenHash(tokens.hash(token));
      access.setInvoiceId(invoiceId); access.setTenantId(pos.tenantId()); access.setPosId(pos.posId());
      access.setDistributionPartnerId(invoice.getDistributionPartnerId()); access.setIssuedBy(pos.subject());
      // Mongo stores Instants in milliseconds: the first response and retries must agree exactly.
      Instant now = Instant.ofEpochMilli(clock.millis());
      access.setIssuedAt(now); access.setExpiresAt(now.plus(ttl).truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
      try { access = receipts.insert(access); }
      catch (DuplicateKeyException ex) {
        access = receipts.findByIdAndInvoiceIdAndTenantIdAndPosId(id, invoiceId, pos.tenantId(), pos.posId())
            .orElseThrow(() -> new IllegalStateException("Receipt issuance conflict; retry with the same requestId"));
      }
    }
    if (access.getRevokedAt() != null || !access.getExpiresAt().isAfter(clock.instant()))
      throw new IllegalStateException("Receipt expired or revoked; use a new requestId to reissue");
    if (!tokens.matches(token, access.getTokenHash()))
      throw new IllegalStateException("Receipt signing configuration changed; use a new requestId to reissue");
    return new IssueReceiptResponse(access.getId(), invoiceId, baseUrl + "?token=" + token, access.getExpiresAt());
  }

  public void revoke(String invoiceId, String receiptId) {
    PosIdentity pos = currentPos("RECEIPT_REVOKE");
    invoiceId = posInvoice(invoiceId, pos).getId();
    if (receiptId == null || !receiptId.matches("[a-f0-9]{64}"))
      throw new BadRequestException("Invalid receipt identifier");
    receipts.findByIdAndInvoiceIdAndTenantIdAndPosId(receiptId, invoiceId, pos.tenantId(), pos.posId())
        .orElseThrow(() -> new IllegalArgumentException("Receipt not found"));
    receipts.revoke(receiptId, invoiceId, pos.tenantId(), pos.posId(), clock.instant());
  }

  public PublicReceiptResponse read(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
    var access = receipts.findByTokenHashAndRevokedAtIsNullAndExpiresAtGreaterThan(tokens.hash(token), clock.instant())
        .orElseThrow(this::unavailable);
    var invoice = invoices.findByIdAndTenantIdAndPosIdAndDistributionPartnerId(
        access.getInvoiceId(), access.getTenantId(), access.getPosId(), access.getDistributionPartnerId())
        .filter(value -> value.getStatus() == TransactionStatus.ACCEPTED).orElseThrow(this::unavailable);
    List<PublicReceiptResponse.Item> items = invoice.getItems().stream()
        .map(item -> new PublicReceiptResponse.Item(item.productCode(), item.productName(),
            item.quantity(), item.unitPrice(), item.amount())).toList();
    return new PublicReceiptResponse(invoice.getInvoiceNumber(), invoice.getTransactionTime(),
        invoice.getDistributionPartnerId(), invoice.getCurrency(), invoice.getTotalAmount(), items);
  }

  private Invoice posInvoice(String invoiceId, PosIdentity pos) {
    if (invoiceId == null || !ObjectId.isValid(invoiceId)) throw new BadRequestException("Invalid invoice identifier");
    return invoices.findByIdAndTenantIdAndPosIdAndDistributionPartnerId(
        invoiceId, pos.tenantId(), pos.posId(), pos.partnerId())
        .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
  }

  private PosIdentity currentPos(String permission) {
    var permissions = AuthenticationContext.getPermissions();
    if (permissions == null || !permissions.contains(permission))
      throw new ForbiddenException("Missing " + permission + " permission");
    var claims = AuthenticationContext.getClaims();
    String tenantId = claims.get("tenantId", String.class);
    String subject = claims.getSubject();
    if (blank(tenantId) || blank(subject)) throw new ForbiddenException("POS token must identify tenant and subject");
    var provider = providers.getIfAvailable();
    if (provider == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Sale Orchestrator provider unavailable");
    SaleOrchestratorConfig binding = provider.findForPos(tenantId, subject)
        .orElseThrow(() -> new ForbiddenException("POS is not registered for this tenant"));
    if (!tenantId.equals(binding.tenantId()) || blank(binding.posId()) || blank(binding.distributionPartnerId()) || !binding.active())
      throw new ForbiddenException("Invalid or inactive POS binding");
    return new PosIdentity(tenantId, subject, binding.posId(), binding.distributionPartnerId());
  }

  private String publicBaseUrl() {
    String base = properties.getPublicBaseUrl();
    try {
      URI uri = URI.create(base);
      if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
          || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
        throw new IllegalArgumentException();
      return base.replaceAll("/+$", "");
    } catch (IllegalArgumentException | NullPointerException ex) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Receipt public URL is not configured correctly");
    }
  }

  private boolean blank(String value) { return value == null || value.isBlank(); }
  private IllegalArgumentException unavailable() { return new IllegalArgumentException("Receipt unavailable"); }
  private record PosIdentity(String tenantId, String subject, String posId, String partnerId) {}
}
