package com.ecofood.app.transaction.repository;

import com.ecofood.app.transaction.entity.Invoice;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface InvoiceRepository extends MongoRepository<Invoice, String>, InvoiceRepositoryCustom {
  boolean existsByTenantIdAndPosIdAndInvoiceNumber(String tenantId, String posId, String invoiceNumber);
}
