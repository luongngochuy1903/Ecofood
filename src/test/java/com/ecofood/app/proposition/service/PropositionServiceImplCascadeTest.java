package com.ecofood.app.proposition.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.proposition.entity.Plan;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.repository.PropositionRepository;
import com.ecofood.core.client.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.PlanStatusEnum;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.SequenceGeneratorService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Covers the Product-approval -> Plan cascade added to PropositionServiceImpl
 * (pendingPlanApprovals/demoteApprovedPlans/approvePlans), called only from
 * the already-authorized ProductServiceImpl.approve flow — these bypass the
 * normal per-actor Tenant authorization and domain validation on purpose, so
 * they're exercised directly against the repository/content-client mocks
 * rather than through the Tenant-gated public API surface.
 */
@ExtendWith(MockitoExtension.class)
class PropositionServiceImplCascadeTest {
  @Mock PropositionRepository repository;
  @Mock CoreProductQueryClient products;
  @Mock CoreEnterpriseQueryClient enterprises;
  @Mock CoreProductProcessClient productProcesses;
  @Mock PropositionAccess access;
  @Mock PropositionValidation validation;
  @Mock PropositionParties parties;
  @Mock PropositionCurrencies currencies;
  @Mock PropositionMapper mapper;
  @Mock SequenceGeneratorService sequenceGeneratorService;

  private PropositionServiceImpl service;
  private ObjectId productId;

  @BeforeEach
  void init() {
    service =
        new PropositionServiceImpl(
            repository,
            products,
            enterprises,
            productProcesses,
            access,
            validation,
            parties,
            currencies,
            mapper,
            sequenceGeneratorService);
    productId = new ObjectId();
  }

  @AfterEach
  void cleanup() {
    AuthenticationContext.unload();
  }

  private void actAs(ObjectId actor) {
    String secret =
        Base64.getEncoder()
            .encodeToString("proposition-cascade-test-secret-at-least-32-bytes".getBytes());
    AuthenticationContext.setSecret(secret);
    AuthenticationContext.setToken(
        Jwts.builder()
            .subject(actor.toHexString())
            .signWith(Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(secret)))
            .compact());
  }

  private Plan plan(PlanStatusEnum status, int productVersion, boolean deleted) {
    Plan p =
        Plan.builder().version(1).productVersion(productVersion).code("PL00001").status(status)
            .build();
    p.setId(new ObjectId());
    p.setDeleted(deleted);
    return p;
  }

  private PropositionEntity proposition(String name, ObjectId productId, Plan... plans) {
    PropositionEntity p =
        PropositionEntity.builder()
            .enterpriseId(new ObjectId())
            .owningTenantId(new ObjectId())
            .productId(productId)
            .enterpriseName("Enterprise")
            .definition(
                name == null
                    ? null
                    : new PropositionDefinition(
                        name, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null))
            .plans(List.of(plans))
            .build();
    p.setId(new ObjectId());
    return p;
  }

  private ProductProcessResponse contentNamed(String productName) {
    var definition =
        new ProductProcessElementResponse<ProductDefinitionAttributeResponse>(
            "def-id",
            "definition",
            List.of(
                new ProductDefinitionAttributeResponse(
                    "attr-id", "process-id", 0L, productName, "CODE", "Category", null, null,
                    List.of(), null)));
    return new ProductProcessResponse(
        "process-id", "family-id", 1, 0L, null, "definition", definition, null, null, false, null,
        null, null, null, null);
  }

  @Test
  void pendingPlanApprovalsReturnsOnlyPendingReviewPlansAtTheGivenVersion() {
    Plan wanted = plan(PlanStatusEnum.PENDING_REVIEW, 2, false);
    Plan wrongVersion = plan(PlanStatusEnum.PENDING_REVIEW, 1, false);
    Plan wrongStatus = plan(PlanStatusEnum.APPROVED, 2, false);
    Plan deletedPlan = plan(PlanStatusEnum.PENDING_REVIEW, 2, true);
    PropositionEntity prop1 = proposition("Prop A", productId, wanted, wrongVersion, wrongStatus, deletedPlan);

    Plan noContent = plan(PlanStatusEnum.PENDING_REVIEW, 2, false);
    PropositionEntity prop2 = proposition(null, productId, noContent);

    when(repository.findByProductIdAndDeletedFalse(productId)).thenReturn(List.of(prop1, prop2));
    when(productProcesses.get(wanted.getIdAsString())).thenReturn(contentNamed("Milk Combo"));
    when(productProcesses.get(noContent.getIdAsString()))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan content not found"));

    List<PendingPlanApprovalResponse> result =
        service.pendingPlanApprovals(productId.toHexString(), 2);

    assertThat(result).hasSize(2);
    var first =
        result.stream().filter(r -> r.planId().equals(wanted.getIdAsString())).findFirst().get();
    assertThat(first.propositionId()).isEqualTo(prop1.getIdAsString());
    assertThat(first.propositionName()).isEqualTo("Prop A");
    assertThat(first.planCode()).isEqualTo("PL00001");
    assertThat(first.planName()).isEqualTo("Milk Combo");

    var second =
        result.stream().filter(r -> r.planId().equals(noContent.getIdAsString())).findFirst().get();
    assertThat(second.propositionName()).isNull();
    // Legacy-resilience: a Plan whose content was never created (or was
    // removed) must not take the whole response down with it.
    assertThat(second.planName()).isNull();
  }

  @Test
  void pendingPlanApprovalsReturnsEmptyForAnInvalidOrMissingProductId() {
    assertThat(service.pendingPlanApprovals(null, 1)).isEmpty();
    assertThat(service.pendingPlanApprovals("not-an-object-id", 1)).isEmpty();
    verifyNoInteractions(repository);
  }

  @Test
  void demoteApprovedPlansMovesMatchingApprovedPlansBackToPendingReviewAndClearsApproval() {
    actAs(new ObjectId());
    Plan toDemote = plan(PlanStatusEnum.APPROVED, 2, false);
    toDemote.setApprovedAt(Instant.now());
    toDemote.setApprovedBy("someone");
    Plan wrongVersion = plan(PlanStatusEnum.APPROVED, 1, false);
    Plan notApproved = plan(PlanStatusEnum.PENDING_REVIEW, 2, false);
    Plan deletedPlan = plan(PlanStatusEnum.APPROVED, 2, true);
    PropositionEntity prop =
        proposition("Prop A", productId, toDemote, wrongVersion, notApproved, deletedPlan);
    when(repository.findByProductIdAndDeletedFalse(productId)).thenReturn(List.of(prop));

    service.demoteApprovedPlans(productId.toHexString(), 2);

    assertThat(toDemote.getStatus()).isEqualTo(PlanStatusEnum.PENDING_REVIEW);
    assertThat(toDemote.getApprovedAt()).isNull();
    assertThat(toDemote.getApprovedBy()).isNull();
    assertThat(wrongVersion.getStatus()).isEqualTo(PlanStatusEnum.APPROVED);
    assertThat(notApproved.getStatus()).isEqualTo(PlanStatusEnum.PENDING_REVIEW);
    assertThat(deletedPlan.getStatus()).isEqualTo(PlanStatusEnum.APPROVED);
    assertThat(prop.getChanges())
        .anyMatch(
            c ->
                "PRODUCT_VERSION_DEMOTED_PLAN".equals(c.action())
                    && "PRODUCT_VERSION_REVIEW".equals(c.actorId())
                    && toDemote.getIdAsString().equals(c.planId()));
    verify(repository).save(prop);
  }

  @Test
  void demoteApprovedPlansDoesNothingWhenNoPlanMatches() {
    Plan untouched = plan(PlanStatusEnum.APPROVED, 1, false);
    PropositionEntity prop = proposition("Prop A", productId, untouched);
    when(repository.findByProductIdAndDeletedFalse(productId)).thenReturn(List.of(prop));

    service.demoteApprovedPlans(productId.toHexString(), 2);

    assertThat(untouched.getStatus()).isEqualTo(PlanStatusEnum.APPROVED);
    verify(repository, never()).save(any());
  }

  @Test
  void approvePlansApprovesOnlyRequestedPendingReviewPlansAtTheGivenVersion() {
    ObjectId actor = new ObjectId();
    actAs(actor);
    Plan requested = plan(PlanStatusEnum.PENDING_REVIEW, 2, false);
    Plan notRequested = plan(PlanStatusEnum.PENDING_REVIEW, 2, false);
    Plan wrongVersion = plan(PlanStatusEnum.PENDING_REVIEW, 1, false);
    Plan alreadyApproved = plan(PlanStatusEnum.APPROVED, 2, false);
    PropositionEntity prop =
        proposition("Prop A", productId, requested, notRequested, wrongVersion, alreadyApproved);
    when(repository.findByProductIdAndDeletedFalse(productId)).thenReturn(List.of(prop));

    service.approvePlans(
        productId.toHexString(),
        2,
        List.of(requested.getIdAsString(), wrongVersion.getIdAsString(), new ObjectId().toHexString()));

    assertThat(requested.getStatus()).isEqualTo(PlanStatusEnum.APPROVED);
    assertThat(requested.getApprovedAt()).isNotNull();
    assertThat(requested.getApprovedBy()).isEqualTo(actor.toHexString());
    assertThat(notRequested.getStatus()).isEqualTo(PlanStatusEnum.PENDING_REVIEW);
    assertThat(wrongVersion.getStatus()).isEqualTo(PlanStatusEnum.PENDING_REVIEW);
    assertThat(alreadyApproved.getStatus()).isEqualTo(PlanStatusEnum.APPROVED);
    assertThat(prop.getChanges())
        .anyMatch(
            c ->
                "APPROVE_PLAN".equals(c.action())
                    && actor.toHexString().equals(c.actorId())
                    && requested.getIdAsString().equals(c.planId()));
    verify(repository).save(prop);
  }

  @Test
  void approvePlansDoesNothingWhenNoPlanIdsOrProductIdAreGiven() {
    service.approvePlans(null, 1, List.of("x"));
    service.approvePlans(productId.toHexString(), 1, null);
    service.approvePlans(productId.toHexString(), 1, List.of());
    verifyNoInteractions(repository);
  }
}
