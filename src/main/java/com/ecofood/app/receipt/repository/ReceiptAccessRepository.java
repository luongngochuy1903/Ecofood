package com.ecofood.app.receipt.repository;

import com.ecofood.app.receipt.entity.ReceiptAccess;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

public interface ReceiptAccessRepository extends MongoRepository<ReceiptAccess, String> {
  Optional<ReceiptAccess> findByTokenHashAndRevokedAtIsNullAndExpiresAtGreaterThan(String hash, Instant now);
  Optional<ReceiptAccess> findByIdAndInvoiceIdAndTenantIdAndPosId(
      String id, String invoiceId, String tenantId, String posId);

  @Query("{ '_id': ?0, 'invoiceId': ?1, 'tenantId': ?2, 'posId': ?3, 'revokedAt': null }")
  @Update("{ '$set': { 'revokedAt': ?4 } }")
  long revoke(String id, String invoiceId, String tenantId, String posId, Instant now);
}
