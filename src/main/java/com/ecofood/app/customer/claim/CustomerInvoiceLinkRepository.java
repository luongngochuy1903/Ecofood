package com.ecofood.app.customer.claim;

import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CustomerInvoiceLinkRepository extends MongoRepository<CustomerInvoiceLink, ObjectId> {
  // Include all records, even soft-deleted: MVP ownership cannot silently be reassigned.
  Optional<CustomerInvoiceLink> findByInvoiceId(String invoiceId);
}
