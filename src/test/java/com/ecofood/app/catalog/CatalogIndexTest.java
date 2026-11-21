package com.ecofood.app.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.*;

class CatalogIndexTest {
  @Test void versionIdentityHasUniqueTenantTypeApiVersionIndexWithoutChangingExistingCollections() throws Exception {
    var mongo=mock(MongoTemplate.class); var indexes=mock(IndexOperations.class); when(mongo.indexOps(ApiPublication.class)).thenReturn(indexes);
    new CatalogMongoConfig().catalogPublicationIndexes(mongo).run(new org.springframework.boot.DefaultApplicationArguments());
    var definitions=org.mockito.ArgumentCaptor.forClass(IndexDefinition.class); verify(indexes,times(2)).createIndex(definitions.capture());
    var identity=definitions.getAllValues().stream().filter(i->Boolean.TRUE.equals(i.getIndexOptions().getBoolean("unique"))).findFirst().orElseThrow();
    assertEquals(java.util.Set.of("tenantId","catalogType","apiKey","version"),identity.getIndexKeys().keySet()); verify(mongo,times(2)).indexOps(ApiPublication.class); verifyNoMoreInteractions(mongo);
  }
}
