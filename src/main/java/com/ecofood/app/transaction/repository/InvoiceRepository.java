package com.ecofood.app.transaction.repository;

import com.ecofood.app.transaction.entity.Invoice;
import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface InvoiceRepository extends MongoRepository<Invoice, String> {
  boolean existsByTenantIdAndPosIdAndInvoiceNumber(
      String tenantId, String posId, String invoiceNumber);

  Optional<Invoice> findByIdAndTenantId(String id, String tenantId);

  Page<Invoice> findByTenantId(String tenantId, Pageable page);
}
