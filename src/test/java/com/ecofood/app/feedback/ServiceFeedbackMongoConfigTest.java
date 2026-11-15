package com.ecofood.app.feedback;

import com.ecofood.app.feedback.config.ServiceFeedbackMongoConfig;
import com.ecofood.app.feedback.entity.ServiceFeedback;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceFeedbackMongoConfigTest {
  @Test void createsUnconditionalUniqueTenantInvoiceIndexEvenWithoutAutoIndexCreation() throws Exception {
    MongoTemplate mongo = mock(MongoTemplate.class);
    IndexOperations indexes = mock(IndexOperations.class);
    when(mongo.indexOps(ServiceFeedback.class)).thenReturn(indexes);
    new ServiceFeedbackMongoConfig().serviceFeedbackUniqueIndex(mongo).run(null);
    var captured = org.mockito.ArgumentCaptor.forClass(IndexDefinition.class);
    verify(indexes).ensureIndex(captured.capture());
    assertEquals(new Document("tenant_identifier", 1).append("invoice_identifier", 1), captured.getValue().getIndexKeys());
    assertEquals(true, captured.getValue().getIndexOptions().get("unique"));
    assertFalse(captured.getValue().getIndexOptions().containsKey("partialFilterExpression"));
  }
}
