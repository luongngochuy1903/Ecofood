package com.ecofood.app.customer.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.userACL.entity.UserEntity;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

class CustomerIdentityMongoConfigTest {
  @Test void uniqueIdentityAndNormalizedEmailIndexesExcludeLegacyUsersWithMissingFields() throws Exception {
    var mongo = mock(MongoTemplate.class); var indexes = mock(IndexOperations.class);
    when(mongo.indexOps(UserEntity.class)).thenReturn(indexes);
    new CustomerIdentityMongoConfig().customerIdentityIndexes(mongo).run(null);
    var captured = org.mockito.ArgumentCaptor.forClass(IndexDefinition.class);
    verify(indexes, times(2)).createIndex(captured.capture());
    var identity = captured.getAllValues().get(0);
    assertEquals(new Document("customer_identity_issuer", 1).append("external_login_id", 1), identity.getIndexKeys());
    for (var index : captured.getAllValues()) {
      assertEquals(true, index.getIndexOptions().get("unique"));
      assertTrue(index.getIndexOptions().containsKey("partialFilterExpression"));
    }
    assertEquals(new Document("customer_email_normalized", 1), captured.getAllValues().get(1).getIndexKeys());
  }
}
