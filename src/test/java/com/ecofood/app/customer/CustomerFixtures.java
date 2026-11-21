package com.ecofood.app.customer;

import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.core.dto.InvoiceItemDto;
import com.ecofood.core.enums.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.bson.types.ObjectId;

final class CustomerFixtures {
  static final String TENANT = "111111111111111111111111", OTHER_TENANT = "222222222222222222222222", A = "333333333333333333333333", B = "444444444444444444444444", SELLER = "555555555555555555555555", INVOICE = "666666666666666666666666";
  static CustomerSettings settings() { return new CustomerSettings(TENANT, Base64.getEncoder().encodeToString("unit-test-customer-receipt-secret-32-bytes".getBytes()), "https://customer.example.test", 24); }
  static CustomerUser user(String id) {
    var user = new CustomerUser(); user.setId(new ObjectId(id)); user.setUsername("customer-" + id); user.setEmail(id + "@example.org"); user.setFirstName("First"); user.setLastName("Last");
    user.setExternalLoginId("kc-" + id); user.setCustomerEmailVerifiedAt(Instant.now()); user.setCustomerProfileRevision(0L);
    user.setCustomerVerifiedEmailHash(CustomerReceiptTokens.hash(user.getEmail()));
    user.setUserCategory(UserCategory.builder().userCategoryCode("CUSTOMER").build());
    user.setPartyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(TENANT)).relationshipTypeCode("TENANT").build())); return user;
  }
  static CustomerAccess.Actor actor(String id) { return new CustomerAccess.Actor(id, TENANT, user(id)); }
  static Invoice invoice() {
    var i = new Invoice(); i.setId(INVOICE); i.setTenantId(TENANT); i.setSubmittedBy(SELLER); i.setPosId(SELLER); i.setSubmissionId("paid-event");
    i.setStatus(TransactionStatus.ACCEPTED); i.setPaymentStatus("PAID"); i.setInvoiceNumber("INV-001"); i.setCurrency("USD"); i.setTotalAmount(new BigDecimal("20")); i.setTransactionTime(Instant.now());
    i.setPropositionId("prop"); i.setPlanId("plan"); i.setOrchestratorId("orchestrator"); i.setOrchestratorVersion(2);
    i.setItems(List.of(new InvoiceItemDto("P1", "Product", BigDecimal.ONE, new BigDecimal("20"), new BigDecimal("20")))); return i;
  }
}
