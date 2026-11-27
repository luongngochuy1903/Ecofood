package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.complaint.config.ComplaintMongoConfig;
import com.ecofood.app.complaint.entity.Complaint;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

class ComplaintQuotaIndexTest {
  @Test void quotaSlotsAreUniquePerAccountAndHistoricalRowsWithoutSlotsAreExcluded() throws Exception {
    var mongo=mock(MongoTemplate.class); var indexes=mock(IndexOperations.class);
    when(mongo.indexOps(Complaint.class)).thenReturn(indexes);
    var config=new ComplaintMongoConfig();
    var method=ComplaintMongoConfig.class.getDeclaredMethod("complaintIndexes",MongoTemplate.class);
    method.setAccessible(true);
    ((org.springframework.boot.ApplicationRunner)method.invoke(config,mongo)).run(mock(ApplicationArguments.class));
    var definitions=ArgumentCaptor.forClass(IndexDefinition.class);
    verify(indexes,times(4)).createIndex(definitions.capture());
    var quota=definitions.getAllValues().stream().filter(index->"uq_complaint_customer_slot".equals(index.getIndexOptions().get("name"))).findFirst().orElseThrow();
    assertEquals(new Document("customerId",1).append("customerComplaintSlot",1),quota.getIndexKeys());
    assertEquals(true,quota.getIndexOptions().get("unique"));
    assertEquals(new Document("customerComplaintSlot",new Document("$exists",true)),quota.getIndexOptions().get("partialFilterExpression"));
  }
}
