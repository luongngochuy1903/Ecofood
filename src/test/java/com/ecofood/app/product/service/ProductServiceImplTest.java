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
import com.ecofood.core.client.CoreEnterpriseQueryClient;
import com.ecofood.core.client.CoreUserQueryClient;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.PropositionService;
import com.ecofood.core.service.SequenceGeneratorService;
import org.springframework.dao.OptimisticLockingFailureException;
@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {
  @Mock ProductRepository products;
  @Mock ProductProcessStore versions;
  @Mock ProductAuthorization auth;
  @Mock ProductAuditService audit;
  @Mock ProductVersionReviewService reviews;
  @Mock PropositionService propositions;
  @Mock SequenceGeneratorService sequenceGeneratorService;
  @Mock CoreEnterpriseQueryClient enterpriseQueryClient;
  @Mock CoreUserQueryClient userQueryClient;
  private ProductServiceImpl service;
  private ObjectId enterprise;
  @BeforeEach void init(){
    lenient().when(enterpriseQueryClient.getById(any())).thenReturn(
        EnterpriseResponse.builder().enterpriseId("e").enterpriseCompanyName("Test Enterprise").build());
    service=new ProductServiceImpl(products,versions,auth,new ProductValidation(),audit,reviews,propositions,sequenceGeneratorService,
        new ProductProcessMapper(enterpriseQueryClient,userQueryClient));
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
    return new ProductUpdateRequest("Updated milk", null, List.of(), null, null, revision, "MILK", "Drinks", null);
  }
  private void stubSaves(){
    when(products.save(any())).thenAnswer(i->{ProductEntity p=i.getArgument(0); return p;});
    when(versions.save(any())).thenAnswer(i->{ProductProcessEntity v=i.getArgument(0);v.setId(new ObjectId());v.setRevision(0L);return v;});
  }
  @Test void createsDraftWithOwnerFromServerNotRequest(){
    allow();stubSaves();
    ProductResponse result=service.create(new ProductCreateRequest(ProductTypeEnum.STANDALONE,"Milk",null,
        List.of(),null,new ProductPricingRequest(BigDecimal.TEN,BigDecimal.ONE,"VND"), "Drinks", null));
    assertThat(result.status()).isEqualTo(ProductStatusEnum.DRAFT);
    assertThat(result.enterpriseId()).isEqualTo(enterprise.toHexString());
    verify(audit).record(any(),any(),eq("CREATE"),any());
  }
  @Test void cannotApproveDraft(){
    ObjectId id=new ObjectId();
    ProductEntity p=ProductEntity.builder().enterpriseId(enterprise).latestVersion(1).build();p.setId(id);
    when(products.findById(id)).thenReturn(Optional.of(p));
    ProductProcessEntity v=ProductProcessEntity.builder().productId(id).version(1)
      .status(ProductStatusEnum.DRAFT).build();
    when(versions.findByProductIdAndVersion(id,1)).thenReturn(Optional.of(v));
    assertThatThrownBy(()->service.approve(id.toHexString(),1,null))
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
    // No user record found for this actor (unstubbed) — approvedBy falls back to the raw id.
    ProductResponse response = service.approve(v.getProductId().toHexString(),v.getVersion(),null);
    assertThat(response.status()).isEqualTo(ProductStatusEnum.APPROVED);
    assertThat(response.approvedBy()).isEqualTo(actor.toHexString());
    assertThat(response.approvedAt()).isNotNull();
    verify(audit).record(any(), eq(v), eq("APPROVE"), any());
  }
  @Test void approvedByResolvesToUserDisplayNameViaCoreUserQueryClient() {
    ProductProcessEntity v = existing(ProductStatusEnum.PENDING_REVIEW);
    ObjectId actor = new ObjectId();
    String secret = java.util.Base64.getEncoder().encodeToString("product-test-secret-with-at-least-32-bytes".getBytes());
    AuthenticationContext.setSecret(secret);
    AuthenticationContext.setToken(io.jsonwebtoken.Jwts.builder().subject(actor.toHexString())
        .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(secret))).compact());
    when(userQueryClient.getById(actor.toHexString())).thenReturn(
        com.ecofood.core.dto.UserQueryResponse.builder().firstName("An").lastName("Tran").build());
    ProductResponse response = service.approve(v.getProductId().toHexString(),v.getVersion(),null);
    assertThat(response.approvedBy()).isEqualTo("Tran An");
  }
  @Test void approveForwardsRequestedPlanIdsToPropositionServiceApprovePlans() {
    ProductProcessEntity v = existing(ProductStatusEnum.PENDING_REVIEW);
    ObjectId actor = new ObjectId();
    String secret = java.util.Base64.getEncoder().encodeToString("product-test-secret-with-at-least-32-bytes".getBytes());
    AuthenticationContext.setSecret(secret);
    AuthenticationContext.setToken(io.jsonwebtoken.Jwts.builder().subject(actor.toHexString())
        .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(secret))).compact());
    service.approve(v.getProductId().toHexString(), v.getVersion(), new ProductApproveRequest(List.of("plan1", "plan2")));
    verify(propositions).approvePlans(v.getProductId().toHexString(), v.getVersion(), List.of("plan1", "plan2"));
  }
  @Test void approveWithNoRequestForwardsNoPlanIds() {
    ProductProcessEntity v = existing(ProductStatusEnum.PENDING_REVIEW);
    ObjectId actor = new ObjectId();
    String secret = java.util.Base64.getEncoder().encodeToString("product-test-secret-with-at-least-32-bytes".getBytes());
    AuthenticationContext.setSecret(secret);
    AuthenticationContext.setToken(io.jsonwebtoken.Jwts.builder().subject(actor.toHexString())
        .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(secret))).compact());
    service.approve(v.getProductId().toHexString(), v.getVersion(), null);
    verify(propositions).approvePlans(v.getProductId().toHexString(), v.getVersion(), List.of());
  }
  @Test void approvingAVersionDemotesTheOtherApprovedVersionsPlansToo() {
    ProductProcessEntity v = existing(ProductStatusEnum.PENDING_REVIEW);
    ObjectId actor = new ObjectId();
    String secret = java.util.Base64.getEncoder().encodeToString("product-test-secret-with-at-least-32-bytes".getBytes());
    AuthenticationContext.setSecret(secret);
    AuthenticationContext.setToken(io.jsonwebtoken.Jwts.builder().subject(actor.toHexString())
        .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(secret))).compact());
    ProductProcessEntity otherApproved = ProductProcessEntity.builder().productId(v.getProductId())
        .version(5).status(ProductStatusEnum.APPROVED).build();
    when(versions.findByProductIdOrderByVersionDesc(v.getProductId())).thenReturn(List.of(otherApproved, v));
    service.approve(v.getProductId().toHexString(), v.getVersion(), null);
    verify(propositions).demoteApprovedPlans(v.getProductId().toHexString(), 5);
  }
  @Test void rejectionRequiresReason() {
    ProductProcessEntity v = existing(ProductStatusEnum.PENDING_REVIEW);
    assertThatThrownBy(() -> service.reject(v.getProductId().toHexString(), v.getVersion(), new ProductDecisionRequest(" ")))
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
  @Test void newVersionCopiesApprovedDefinitionAndLeavesTheSourceVersionApproved() {
    ProductProcessEntity v = existing(ProductStatusEnum.APPROVED);
    when(versions.save(any())).thenAnswer(i -> { ProductProcessEntity next = i.getArgument(0); next.setRevision(0L); return next; });
    ProductResponse response = service.newVersion(v.getProductId().toHexString());
    assertThat(response.version()).isEqualTo(2);
    assertThat(response.status()).isEqualTo(ProductStatusEnum.DRAFT);
    assertThat(response.name()).isEqualTo(v.getName());
    assertThat(response.approvedAt()).isNull();
    // Creating a new draft version must never downgrade the source version
    // it was branched from — it stays exactly APPROVED, with no PENDING_REVIEW
    // cascade queued or run for it.
    assertThat(v.getStatus()).isEqualTo(ProductStatusEnum.APPROVED);
    verifyNoInteractions(reviews);
    verify(audit).record(any(), any(), eq("NEW_VERSION"), any());
  }
  @Test void pendingVersionCannotBeVersionedAgain() {
    ProductProcessEntity v = existing(ProductStatusEnum.PENDING_REVIEW);
    assertThatThrownBy(() -> service.newVersion(v.getProductId().toHexString()))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("APPROVED");
    verify(products, never()).save(any());
  }
  @Test void duplicateCodeRejectedBeforeWrites() {
    allow();
    when(sequenceGeneratorService.generateSequence(ProductEntity.SEQUENCE_NAME)).thenReturn(1L);
    ProductEntity taken = ProductEntity.builder().enterpriseId(enterprise).code("PR00001").build(); taken.setId(new ObjectId());
    when(products.findByEnterpriseIdAndCode(enterprise,"PR00001")).thenReturn(Optional.of(taken));
    assertThatThrownBy(() -> service.create(new ProductCreateRequest(ProductTypeEnum.STANDALONE,"Lunch",null,
        List.of(),null,null,"Meals",null)))
        .hasMessageContaining("already exists");
    verify(products,never()).save(any());
  }
  @Test void createRemovesParentIfInitialVersionSaveFails() {
    allow();
    when(versions.save(any())).thenThrow(new IllegalStateException("write failed"));
    assertThatThrownBy(() -> service.create(new ProductCreateRequest(ProductTypeEnum.STANDALONE,
        "Milk", null, List.of(), null, null, "Drinks", null))).hasMessageContaining("write failed");
    verify(products).deleteById(any());
    verifyNoInteractions(audit);
  }
}
