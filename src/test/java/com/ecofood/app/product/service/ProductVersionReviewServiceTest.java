package com.ecofood.app.product.service;

import com.ecofood.app.product.entity.*;
import com.ecofood.app.product.config.ProductVersionReviewMongoConfig;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.service.PropositionMongoConfiguration;
import com.ecofood.core.enums.*;
import java.util.*;
import org.bson.types.ObjectId;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.*;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
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

  @Test void systemReviewUpdateMapsAndReadsWithoutAUserObjectId() {
    service.apply(product, 1);
    var update = org.mockito.ArgumentCaptor.forClass(Update.class);
    verify(mongo).updateMulti(any(Query.class), update.capture(), eq(PropositionEntity.class));
    var converter = converter();
    var entity = converter.getMappingContext().getRequiredPersistentEntity(PropositionEntity.class);
    var mapped = new UpdateMapper(converter).getMappedObject(update.getValue().getUpdateObject(), entity);
    assertFalse(mapped.get("$set", Document.class).containsKey("updated_by_user_identifier"));
    assertTrue(mapped.get("$unset", Document.class).containsKey("updated_by_user_identifier"));

    var document = new Document("_id", new ObjectId())
        .append("updated_by_user_identifier", new ObjectId());
    document.putAll(mapped.get("$set", Document.class));
    mapped.get("$unset", Document.class).keySet().forEach(document::remove);
    document.put("changes", List.of(mapped.get("$push", Document.class).get("changes")));
    var restored = converter.read(PropositionEntity.class, document);
    assertNull(restored.getUpdatedBy());
    assertEquals(PropositionStatusEnum.PENDING_REVIEW, restored.getStatus());
    assertEquals("PRODUCT_VERSION_REVIEW", restored.getChanges().get(0).actorId());
  }

  @Test void startupRepairTargetsOnlyLegacyActorAndMakesDocumentReadable() throws Exception {
    new ProductVersionReviewMongoConfig().repairProductVersionReviewActor(mongo).run(null);
    var query = org.mockito.ArgumentCaptor.forClass(Query.class);
    var update = org.mockito.ArgumentCaptor.forClass(Update.class);
    verify(mongo).updateMulti(query.capture(), update.capture(), eq(PropositionEntity.class));
    var converter = converter();
    var entity = converter.getMappingContext().getRequiredPersistentEntity(PropositionEntity.class);
    var filter = new QueryMapper(converter).getMappedObject(query.getValue().getQueryObject(), entity);
    assertEquals(new Document("updated_by_user_identifier", "PRODUCT_VERSION_REVIEW"), filter);
    var mapped = new UpdateMapper(converter).getMappedObject(update.getValue().getUpdateObject(), entity);
    assertEquals(new Document("updated_by_user_identifier", 1), mapped.get("$unset"));
    assertEquals(new Document("revision", 1), mapped.get("$inc"));
    assertEquals(2, mapped.size());

    var document = new Document("_id", new ObjectId())
        .append("updated_by_user_identifier", "PRODUCT_VERSION_REVIEW")
        .append("revision", 4L)
        .append("changes", List.of(new Document("action", "PRODUCT_VERSION_PENDING_REVIEW")
            .append("actorId", "PRODUCT_VERSION_REVIEW")));
    assertThrows(ConversionFailedException.class, () -> converter.read(PropositionEntity.class, document));
    mapped.get("$unset", Document.class).keySet().forEach(document::remove);
    document.put("revision", 5L);
    var restored = converter.read(PropositionEntity.class, document);
    assertNull(restored.getUpdatedBy());
    assertEquals(5L, restored.getRevision());
    assertEquals("PRODUCT_VERSION_REVIEW", restored.getChanges().get(0).actorId());
    assertFalse(document.containsKey("updated_by_user_identifier")); // No match on subsequent startups.
  }

  private MappingMongoConverter converter() {
    var conversions = new PropositionMongoConfiguration().propositionMongoConversions();
    var context = new MongoMappingContext();
    context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    context.afterPropertiesSet();
    var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
    converter.setCustomConversions(conversions);
    converter.afterPropertiesSet();
    return converter;
  }
}
