package com.ecofood.app.customer.claim;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.customer.auth.CustomerIdentityService;
import com.ecofood.app.customer.auth.CustomerTenantResolver;
import com.ecofood.app.customer.claim.InvoiceClaimContracts.*;
import com.ecofood.app.receipt.service.ReceiptService;
import com.ecofood.app.receipt.service.ReceiptTokenCodec;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.security.AuthenticationContext;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InvoiceClaimService {
  private static final Duration PROOF_TTL = Duration.ofMinutes(5);
  private final CustomerIdentityService identities;
  private final CustomerTenantResolver portalTenants;
  private final ReceiptService receipts;
  private final ReceiptTokenCodec hashes;
  private final CoreTenantQueryClient tenants;
  private final CustomerInvoiceLinkRepository links;
  private final InvoiceClaimProofRepository proofs;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public Eligibility eligibility(ReceiptRequest request) {
    var customer = currentCustomer();
    var receipt = receipts.requireValidReceipt(request.receiptToken());
    requireSellerTenant(receipt.invoice());
    var existing = links.findByInvoiceId(receipt.invoice().getId()).orElse(null);
    Status status = ownership(existing, customer);
    if (status != Status.ELIGIBLE)
      return new Eligibility(status, receipt.response(), null, null, ownClaimedAt(existing, customer));
    byte[] bytes = new byte[32]; random.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant now = Instant.ofEpochMilli(clock.millis());
    Instant expiresAt = now.plus(PROOF_TTL).isBefore(receipt.access().getExpiresAt())
        ? now.plus(PROOF_TTL) : receipt.access().getExpiresAt();
    var proof = new InvoiceClaimProof(); proof.setId(proofHash(raw));
    proof.setCustomerId(customer.id()); proof.setCustomerTenantId(customer.tenantId());
    proof.setInvoiceId(receipt.invoice().getId()); proof.setTenantId(receipt.invoice().getTenantId());
    proof.setDistributionPartnerId(receipt.invoice().getDistributionPartnerId());
    proof.setReceiptAccessId(receipt.access().getId()); proof.setReceiptTokenHash(receipt.access().getTokenHash());
    proof.setCreatedAt(now); proof.setExpiresAt(expiresAt);
    proofs.insert(proof);
    return new Eligibility(Status.ELIGIBLE, receipt.response(), raw, expiresAt, null);
  }

  public ClaimStatusResponse status(ReceiptRequest request) {
    var customer = currentCustomer();
    var receipt = receipts.requireValidReceipt(request.receiptToken());
    requireSellerTenant(receipt.invoice());
    var existing = links.findByInvoiceId(receipt.invoice().getId()).orElse(null);
    return new ClaimStatusResponse(ownership(existing, customer), ownClaimedAt(existing, customer));
  }

  public ClaimResult claim(ClaimRequest request) {
    var customer = currentCustomer();
    if (request == null || !Boolean.TRUE.equals(request.confirmed()))
      throw new BadRequestException("Customer confirmation is required");
    if (request.claimProof() == null || !request.claimProof().matches("[A-Za-z0-9_-]{43}")) throw proofUnavailable();
    var proof = proofs.findByIdAndCustomerIdAndCustomerTenantId(proofHash(request.claimProof()), customer.id(), customer.tenantId())
        .filter(value -> value.getExpiresAt() != null && value.getExpiresAt().isAfter(clock.instant()))
        .orElseThrow(this::proofUnavailable);
    var receipt = receipts.requireValidClaimReceipt(proof.getReceiptAccessId(), proof.getReceiptTokenHash());
    Invoice invoice = receipt.invoice();
    requireSellerTenant(invoice);
    if (!Objects.equals(proof.getInvoiceId(), invoice.getId()) || !Objects.equals(proof.getTenantId(), invoice.getTenantId())
        || !Objects.equals(proof.getDistributionPartnerId(), invoice.getDistributionPartnerId())) throw proofUnavailable();
    var existing = links.findByInvoiceId(invoice.getId()).orElse(null);
    if (existing != null) return ownedResult(existing, customer);
    var link = new CustomerInvoiceLink(); link.setCustomerId(customer.id()); link.setCustomerTenantId(customer.tenantId());
    link.setInvoiceId(invoice.getId()); link.setTenantId(invoice.getTenantId());
    link.setDistributionPartnerId(invoice.getDistributionPartnerId()); link.setClaimStatus(CustomerInvoiceLink.ClaimStatus.CLAIMED);
    link.setClaimedAt(Instant.ofEpochMilli(clock.millis())); link.setReceiptAccessId(receipt.access().getId());
    link.setClaimProofId(proof.getId()); link.setCreatedBy(customer.id());
    link.setId(new ObjectId());
    link.setNotificationOutbox(java.util.List.of(com.ecofood.app.notification.NotificationOutboxEvent.claimed(
        link.getIdAsString(), customer.id(), customer.tenantId(), invoice.getTenantId(), invoice.getDistributionPartnerId(), invoice.getId(), link.getClaimedAt())));
    try { return ownedResult(links.insert(link), customer); }
    catch (DuplicateKeyException ex) {
      var winner = links.findByInvoiceId(invoice.getId()).orElse(null);
      if (winner == null) throw ex;
      return ownedResult(winner, customer);
    }
  }

  private Customer currentCustomer() {
    var claims = AuthenticationContext.getClaims();
    var permissions = AuthenticationContext.getPermissions();
    var portal = portalTenants.resolve();
    if (!"CUSTOMER".equals(claims.get("identityType")) || !"CUSTOMER".equals(claims.get("userCategory"))
        || permissions == null || !permissions.contains("CUSTOMER_INVOICE_CLAIM")
        || !portal.tenantId().equals(claims.get("tenantId")) || !portal.clientId().equals(claims.get("clientId")))
      throw new ForbiddenException("Customer invoice claim access required");
    var user = identities.requireAccount(claims.getSubject());
    return new Customer(user.getIdAsString(), portal.tenantId());
  }

  private void requireSellerTenant(Invoice invoice) {
    if (invoice.getTenantId() == null || !ObjectId.isValid(invoice.getTenantId())) throw new IllegalArgumentException("Receipt unavailable");
    try {
      var tenant = tenants.getById(invoice.getTenantId());
      if (tenant == null || !invoice.getTenantId().equals(tenant.getId())) throw new IllegalArgumentException();
    } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Receipt unavailable"); }
  }
  private Status ownership(CustomerInvoiceLink link, Customer customer) {
    if (link == null) return Status.ELIGIBLE;
    return customer.id().equals(link.getCustomerId()) && customer.tenantId().equals(link.getCustomerTenantId())
        ? Status.CLAIMED_BY_YOU : Status.CLAIMED_BY_ANOTHER_CUSTOMER;
  }
  private Instant ownClaimedAt(CustomerInvoiceLink link, Customer customer) {
    return ownership(link, customer) == Status.CLAIMED_BY_YOU ? link.getClaimedAt() : null;
  }
  private ClaimResult ownedResult(CustomerInvoiceLink link, Customer customer) {
    if (ownership(link, customer) != Status.CLAIMED_BY_YOU) throw new IllegalStateException("Invoice already claimed");
    return new ClaimResult(Status.CLAIMED_BY_YOU, link.getInvoiceId(), link.getIdAsString(), link.getClaimedAt());
  }
  private String proofHash(String raw) { return hashes.hash("invoice-claim-proof:v1:" + raw); }
  private IllegalArgumentException proofUnavailable() { return new IllegalArgumentException("Claim proof unavailable or expired"); }
  private record Customer(String id, String tenantId) {}
}
