package com.ecofood.app.product.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.ecofood.app.product.entity.*;
import com.ecofood.app.product.repository.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import com.ecofood.core.security.AuthenticationContext;
import org.springframework.dao.OptimisticLockingFailureException;
@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {
  @Mock ProductRepository products;
  @Mock ProductProcessStore versions;
  @Mock ProductAuthorization auth;
  @Mock ProductAuditService audit;
  @Mock ProductVersionReviewService reviews;
  private ProductServiceImpl service;
  private ObjectId enterprise;
  @BeforeEach void init(){
    service=new ProductServiceImpl(products,versions,auth,new ProductValidation(),audit,reviews);
    enterprise=new ObjectId();
  }
  private void allow(){ when(auth.requireEnterprise()).thenReturn(enterprise); }
  @AfterEach void cleanup() { AuthenticationContext.unload(); }
  private ProductProcessEntity existing(ProductStatusEnum status) {
    allow();
    ObjectId id = new ObjectId();
    ProductEntity p = ProductEntity.builder().enterpriseId(enterprise).type(ProductTypeEnum.STANDALONE).code("MILK")
        .latestVersion(1).latestStatus(status).revision(0L).build();
    p.setId(id);
    ProductProcessEntity v = ProductProcessEntity.builder().productId(id).version(1).revision(3L)
        .name("Milk").code("MILK").category("Drinks").ingredients(List.of()).status(status)
        .pricing(new ProductPricingRequest(BigDecimal.TEN, BigDecimal.ONE, "VND")).build();
    when(products.findById(id)).thenReturn(Optional.of(p));
    when(versions.findByProductIdAndVersion(id, 1)).thenReturn(Optional.of(v));
    return v;
  }
  private ProductUpdateRequest updateRequest(long revision) {
    return new ProductUpdateRequest("Updated milk", null, List.of(), null, null, revision, "MILK", "Drinks", null, List.of());
  }
  private void stubSaves(){
    when(products.save(any())).thenAnswer(i->{ProductEntity p=i.getArgument(0); return p;});
    when(versions.save(any())).thenAnswer(i->{ProductProcessEntity v=i.getArgument(0);v.setId(new ObjectId());v.setRevision(0L);return v;});
  }
  @Test void createsDraftWithOwnerFromServerNotRequest(){
    allow();stubSaves();
    ProductResponse result=service.create(new ProductCreateRequest(ProductTypeEnum.STANDALONE,"Milk",null,null,
        List.of(),null,new ProductPricingRequest(BigDecimal.TEN,BigDecimal.ONE,"VND"), "MILK", "Drinks", null, List.of()));
    assertThat(result.status()).isEqualTo(ProductStatusEnum.DRAFT);
    assertThat(result.enterpriseId()).isEqualTo(enterprise.toHexString());
    verify(audit).record(any(),any(),eq("CREATE"),any());
  }
  @Test void cannotCreateAdaptedProductWithoutSource(){
    allow();
    assertThatThrownBy(()->service.create(new ProductCreateRequest(ProductTypeEnum.ADAPTED,"Milk",null,null,
      List.of(),null,null, "MILK", "Drinks", null, List.of()))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("sourceProductId");
    verify(products,never()).save(any());
  }
  @Test void cannotApproveDraft(){
    ObjectId id=new ObjectId();
    ProductEntity p=ProductEntity.builder().enterpriseId(enterprise).latestVersion(1).build();p.setId(id);
    when(products.findById(id)).thenReturn(Optional.of(p));
    ProductProcessEntity v=ProductProcessEntity.builder().productId(id).version(1)
      .status(ProductStatusEnum.DRAFT).build();
    when(versions.findByProductIdAndVersion(id,1)).thenReturn(Optional.of(v));
    assertThatThrownBy(()->service.approve(id.toHexString()))
      .isInstanceOf(ResponseStatusException.class).hasMessageContaining("PENDING_REVIEW");
    verify(versions,never()).save(any());
  }
  @Test void submitsDraftForReview() {
    ProductProcessEntity v = existing(ProductStatusEnum.DRAFT);
    assertThat(service.submit(v.getProductId().toHexString()).status()).isEqualTo(ProductStatusEnum.PENDING_REVIEW);
    verify(audit).record(any(), eq(v), eq("SUBMIT"), any());
  }
  @Test void approvesPendingReviewWithoutCreatingProposition() {
    ProductProcessEntity v = existing(ProductStatusEnum.PENDING_REVIEW);
    ObjectId actor = new ObjectId();
    String secret = java.util.Base64.getEncoder().encodeToString("product-test-secret-with-at-least-32-bytes".getBytes());
    AuthenticationContext.setSecret(secret);
    AuthenticationContext.setToken(io.jsonwebtoken.Jwts.builder().subject(actor.toHexString())
        .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(secret))).compact());
    ProductResponse response = service.approve(v.getProductId().toHexString());
    assertThat(response.status()).isEqualTo(ProductStatusEnum.APPROVED);
    assertThat(response.approvedBy()).isEqualTo(actor.toHexString());
    assertThat(response.approvedAt()).isNotNull();
    verify(audit).record(any(), eq(v), eq("APPROVE"), any());
  }
  @Test void rejectionRequiresReason() {
    ProductProcessEntity v = existing(ProductStatusEnum.PENDING_REVIEW);
    assertThatThrownBy(() -> service.reject(v.getProductId().toHexString(), new ProductDecisionRequest(" ")))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("reason");
    verify(versions, never()).save(any());
  }
  @Test void rejectedVersionReturnsToDraftWhenEdited() {
    ProductProcessEntity v = existing(ProductStatusEnum.REJECTED);
    v.setRejectionReason("Missing recipe");
    ProductResponse response = service.update(v.getProductId().toHexString(), updateRequest(3L));
    assertThat(response.status()).isEqualTo(ProductStatusEnum.DRAFT);
    assertThat(response.rejectionReason()).isNull();
    assertThat(response.name()).isEqualTo("Updated milk");
  }
  @Test void approvedVersionCannotBeEdited() {
    ProductProcessEntity v = existing(ProductStatusEnum.APPROVED);
    assertThatThrownBy(() -> service.update(v.getProductId().toHexString(), updateRequest(3L)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("may be edited");
    verify(versions, never()).save(any());
  }
  @Test void staleRevisionIsRejectedBeforeWrite() {
    ProductProcessEntity v = existing(ProductStatusEnum.DRAFT);
    assertThatThrownBy(() -> service.update(v.getProductId().toHexString(), updateRequest(2L)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("reload");
    verify(versions, never()).save(any());
  }
  @Test void concurrentWriteReturnsConflict() {
    ProductProcessEntity v = existing(ProductStatusEnum.DRAFT);
    when(versions.save(any())).thenThrow(new OptimisticLockingFailureException("concurrent update"));
    assertThatThrownBy(() -> service.submit(v.getProductId().toHexString()))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
    verify(products, never()).save(any());
    verifyNoInteractions(audit);
  }
  @Test void newVersionCopiesApprovedDefinitionAndClearsApprovalMetadata() {
    ProductProcessEntity v = existing(ProductStatusEnum.APPROVED);
    when(versions.save(any())).thenAnswer(i -> { ProductProcessEntity next = i.getArgument(0); next.setRevision(0L); return next; });
    ProductResponse response = service.newVersion(v.getProductId().toHexString());
    assertThat(response.version()).isEqualTo(2);
    assertThat(response.status()).isEqualTo(ProductStatusEnum.DRAFT);
    assertThat(response.name()).isEqualTo(v.getName());
    assertThat(response.approvedAt()).isNull();
    assertThat(v.getStatus()).isEqualTo(ProductStatusEnum.APPROVED);
    verify(reviews).apply(any(), eq(1));
    verify(audit).record(any(), any(), eq("NEW_VERSION"), any());
  }
  @Test void pendingVersionCannotBeVersionedAgain() {
    ProductProcessEntity v = existing(ProductStatusEnum.PENDING_REVIEW);
    assertThatThrownBy(() -> service.newVersion(v.getProductId().toHexString()))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("APPROVED");
    verify(products, never()).save(any());
  }
  private ProductEntity reference(ProductTypeEnum type) {
    ProductEntity p = ProductEntity.builder().enterpriseId(enterprise).type(type).latestVersion(1).build();
    p.setId(new ObjectId());
    lenient().when(products.findById(p.getId())).thenReturn(Optional.of(p));
    lenient().when(versions.findByProductIdAndVersion(p.getId(),1)).thenReturn(Optional.of(
        ProductProcessEntity.builder().productId(p.getId()).version(1).name("Reference").build()));
    return p;
  }
  private ProductCreateRequest createRequest(ProductTypeEnum type, String source, List<ProductComponentRequest> components) {
    return new ProductCreateRequest(type,"Lunch",null,source,List.of(),null,null,"LUNCH","Meals",null,components);
  }
  private ProductEntity masterReference(ProductStatusEnum status) {
    var first=reference(ProductTypeEnum.STANDALONE);var second=reference(ProductTypeEnum.STANDALONE);
    var master=reference(ProductTypeEnum.MASTER);
    var process=ProductProcessEntity.builder().productId(master.getId()).version(1).status(status).name("Master").componentProducts(List.of(
        new ProductComponentRequest(first.getIdAsString(),BigDecimal.ONE),new ProductComponentRequest(second.getIdAsString(),BigDecimal.ONE))).build();
    lenient().when(versions.findByProductIdAndVersion(master.getId(),1)).thenReturn(Optional.of(process));
    when(versions.findByProductIdOrderByVersionDesc(master.getId())).thenReturn(List.of(process));
    return master;
  }
  @Test void adaptedUsesApprovedMaster() {
    allow(); stubSaves();
    ProductEntity source = masterReference(ProductStatusEnum.APPROVED);
    ProductResponse response = service.create(createRequest(ProductTypeEnum.ADAPTED,source.getIdAsString(),List.of()));
    assertThat(response.sourceProductId()).isEqualTo(source.getIdAsString());
    assertThat(response.code()).isEqualTo("LUNCH");
    assertThat(response.category()).isEqualTo("Meals");
  }
  @Test void adaptedCannotUseStandaloneAsBase() {
    allow(); ProductEntity source = reference(ProductTypeEnum.STANDALONE);
    assertThatThrownBy(() -> service.create(createRequest(ProductTypeEnum.ADAPTED,source.getIdAsString(),List.of())))
        .hasMessageContaining("MASTER");
    verify(products,never()).save(any());
  }
  @Test void adaptedCannotUseUnapprovedMaster() {
    allow();var source=masterReference(ProductStatusEnum.DRAFT);
    assertThatThrownBy(()->service.create(createRequest(ProductTypeEnum.ADAPTED,source.getIdAsString(),List.of())))
        .hasMessageContaining("APPROVED");
    verify(products,never()).save(any());
  }
  @Test void masterRequiresTwoDistinctComponents() {
    allow();
    assertThatThrownBy(() -> service.create(createRequest(ProductTypeEnum.MASTER,null,List.of())))
        .hasMessageContaining("at least two");
    ProductEntity source = reference(ProductTypeEnum.STANDALONE);
    var item = new ProductComponentRequest(source.getIdAsString(),BigDecimal.ONE);
    assertThatThrownBy(() -> service.create(createRequest(ProductTypeEnum.MASTER,null,List.of(item,item))))
        .hasMessageContaining("Duplicate component");
    verify(products,never()).save(any());
  }
  @Test void masterAcceptsStandaloneAndAdaptedComponents() {
    allow(); stubSaves();
    ProductEntity base = reference(ProductTypeEnum.STANDALONE);
    ProductEntity source=masterReference(ProductStatusEnum.APPROVED);
    ProductEntity adapted = reference(ProductTypeEnum.ADAPTED); adapted.setSourceProductId(source.getId());
    var components = List.of(new ProductComponentRequest(base.getIdAsString(),BigDecimal.ONE),
        new ProductComponentRequest(adapted.getIdAsString(),new BigDecimal("2")));
    assertThat(service.create(createRequest(ProductTypeEnum.MASTER,null,components)).componentProducts()).isEqualTo(components);
  }
  @Test void masterCannotReferenceAnotherMaster() {
    allow(); ProductEntity nested = reference(ProductTypeEnum.MASTER);
    var item = new ProductComponentRequest(nested.getIdAsString(),BigDecimal.ONE);
    assertThatThrownBy(() -> service.create(createRequest(ProductTypeEnum.MASTER,null,List.of(item,
        new ProductComponentRequest(new ObjectId().toHexString(),BigDecimal.ONE)))))
        .hasMessageContaining("STANDALONE or ADAPTED");
  }
  @Test void masterCannotReferenceItselfWhenUpdating() {
    ProductProcessEntity v = existing(ProductStatusEnum.DRAFT);
    ProductEntity p = products.findById(v.getProductId()).orElseThrow(); p.setType(ProductTypeEnum.MASTER);
    var request = new ProductUpdateRequest("Combo",null,List.of(),null,null,3L,"MILK","Meals",null,List.of(
        new ProductComponentRequest(p.getIdAsString(),BigDecimal.ONE),
        new ProductComponentRequest(new ObjectId().toHexString(),BigDecimal.ONE)));
    assertThatThrownBy(() -> service.update(p.getIdAsString(),request)).hasMessageContaining("Circular");
    verify(versions,never()).save(any());
  }
  @Test void duplicateCodeRejectedBeforeWrites() {
    allow(); ProductEntity taken = ProductEntity.builder().enterpriseId(enterprise).code("LUNCH").build(); taken.setId(new ObjectId());
    when(products.findByEnterpriseIdAndCode(enterprise,"LUNCH")).thenReturn(Optional.of(taken));
    assertThatThrownBy(() -> service.create(createRequest(ProductTypeEnum.STANDALONE,null,List.of())))
        .hasMessageContaining("already exists");
    verify(products,never()).save(any());
  }
  @Test void createRemovesParentIfInitialVersionSaveFails() {
    allow();
    when(versions.save(any())).thenThrow(new IllegalStateException("write failed"));
    assertThatThrownBy(() -> service.create(new ProductCreateRequest(ProductTypeEnum.STANDALONE,
        "Milk", null, null, List.of(), null, null, "MILK", "Drinks", null, List.of()))).hasMessageContaining("write failed");
    verify(products).deleteById(any());
    verifyNoInteractions(audit);
  }
}
