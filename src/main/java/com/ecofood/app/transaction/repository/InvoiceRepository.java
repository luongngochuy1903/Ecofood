package com.ecofood.app.transaction.repository;

import com.ecofood.app.transaction.entity.Invoice;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface InvoiceRepository extends MongoRepository<Invoice, String> {
  Optional<Invoice> findByIdAndTenantId(String id, String tenantId);

  Page<Invoice> findByTenantIdAndIdIn(String tenantId, java.util.Collection<String> ids, Pageable page);

  Page<Invoice> findByTenantId(String tenantId, Pageable page);

  List<Invoice> findBySubmissionPendingTrueAndSubmissionRetryAtLessThanEqual(
      Instant retryAt, Pageable page);
}
