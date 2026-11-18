package com.ecofood.app.customer.claim;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

class InvoiceClaimMongoConfigTest {
  @Test void permanentUniqueInvoiceOwnerAndProofTtlAreCreated() throws Exception {
    var mongo = mock(MongoTemplate.class); var links = mock(IndexOperations.class); var proofs = mock(IndexOperations.class);
    when(mongo.indexOps(CustomerInvoiceLink.class)).thenReturn(links); when(mongo.indexOps(InvoiceClaimProof.class)).thenReturn(proofs);
    new InvoiceClaimMongoConfig().invoiceClaimIndexes(mongo).run(null);
    var captured = org.mockito.ArgumentCaptor.forClass(IndexDefinition.class);
    verify(links, times(2)).createIndex(captured.capture());
    var unique = captured.getAllValues().get(0);
    assertEquals(new Document("invoice_identifier", 1), unique.getIndexKeys());
    assertEquals(true, unique.getIndexOptions().get("unique")); assertFalse(unique.getIndexOptions().containsKey("partialFilterExpression"));
    verify(proofs).createIndex(captured.capture());
    assertEquals(new Document("expiresAt", 1), captured.getValue().getIndexKeys());
    assertEquals(0L, ((Number) captured.getValue().getIndexOptions().get("expireAfterSeconds")).longValue());
  }
}
