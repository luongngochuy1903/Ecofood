package com.ecofood.app.customer.claim;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface InvoiceClaimProofRepository extends MongoRepository<InvoiceClaimProof, String> {
  Optional<InvoiceClaimProof> findByIdAndCustomerIdAndCustomerTenantId(String id, String customerId, String customerTenantId);
}
