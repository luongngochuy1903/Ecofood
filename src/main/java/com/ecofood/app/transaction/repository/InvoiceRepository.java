package com.ecofood.app.transaction.repository;

import com.ecofood.app.transaction.entity.Invoice;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.Optional;

public interface InvoiceRepository extends MongoRepository<Invoice, String>, InvoiceRepositoryCustom {
  boolean existsByTenantIdAndPosIdAndInvoiceNumber(String tenantId, String posId, String invoiceNumber);
  Optional<Invoice> findByIdAndTenantIdAndPosIdAndDistributionPartnerId(
      String id, String tenantId, String posId, String distributionPartnerId);
}
