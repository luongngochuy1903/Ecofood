package com.ecofood.app.product.service;

import com.ecofood.app.product.entity.*;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.core.enums.*;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductVersionReviewServiceTest {
  private final ProductProcessStore processes = mock(ProductProcessStore.class);
  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final ProductVersionReviewService service = new ProductVersionReviewService(processes, mongo);
  private ProductEntity product; private ProductProcessEntity source;
  @BeforeEach void setup() {
    product = ProductEntity.builder().enterpriseId(new ObjectId()).pendingVersionReviews(List.of(1)).build(); product.setId(new ObjectId());
    source = ProductProcessEntity.builder().productId(product.getId()).version(1).status(ProductStatusEnum.APPROVED).approvedBy("old-approver").approvedAt(java.time.Instant.now()).build();
    when(processes.findByProductIdAndVersion(product.getId(), 1)).thenReturn(Optional.of(source));
  }
  @Test void marksSourceAndOnlyMatchingPropositionsPendingWithRevisionAndAudit() {
    service.apply(product, 1);
    assertEquals(ProductStatusEnum.PENDING_REVIEW, source.getStatus()); assertNull(source.getApprovedAt()); assertNull(source.getApprovedBy());
    verify(processes).save(source);
    var query = org.mockito.ArgumentCaptor.forClass(Query.class); var update = org.mockito.ArgumentCaptor.forClass(Update.class);
    verify(mongo).updateMulti(query.capture(), update.capture(), eq(PropositionEntity.class));
    var filter = query.getValue().getQueryObject();
    assertEquals(product.getId(), filter.get("productId")); assertEquals(product.getEnterpriseId(), filter.get("enterpriseId")); assertEquals(1, filter.get("productVersion"));
    assertEquals(PropositionStatusEnum.PENDING_REVIEW, update.getValue().getUpdateObject().get("$set", org.bson.Document.class).get("status"));
    assertTrue(update.getValue().getUpdateObject().containsKey("$push")); assertTrue(update.getValue().getUpdateObject().containsKey("$inc"));
    verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(ProductEntity.class));
  }
  @Test void retriesDoNotRewriteAlreadyPendingSource() {
    source.setStatus(ProductStatusEnum.PENDING_REVIEW); service.apply(product,1);
    verify(processes, never()).save(any()); verify(mongo).updateMulti(any(Query.class),any(Update.class),eq(PropositionEntity.class));
  }
  @Test void failureLeavesDurableWorkUnacknowledged() {
    when(mongo.updateMulti(any(Query.class), any(Update.class), eq(PropositionEntity.class))).thenThrow(new IllegalStateException("offline"));
    assertThrows(IllegalStateException.class, () -> service.apply(product,1));
    verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(ProductEntity.class));
  }
}
