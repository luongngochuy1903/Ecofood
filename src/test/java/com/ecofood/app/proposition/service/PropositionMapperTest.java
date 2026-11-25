package com.ecofood.app.proposition.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.proposition.entity.Plan;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.core.client.CoreProductProcessClient;
import com.ecofood.core.client.CoreUserQueryClient;
import com.ecofood.core.dto.ProductProcessResponse;
import com.ecofood.core.dto.PropositionDefinition;
import com.ecofood.core.dto.UserQueryResponse;
import com.ecofood.core.enums.PlanStatusEnum;
import java.time.Instant;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class PropositionMapperTest {
  private final PropositionAccess access = mock(PropositionAccess.class);
  private final CoreProductProcessClient productProcesses = mock(CoreProductProcessClient.class);
  private final CoreUserQueryClient userQueryClient = mock(CoreUserQueryClient.class);
  private final PropositionMapper mapper = new PropositionMapper(
      access, mock(PropositionParties.class), mock(PropositionCurrencies.class), productProcesses, userQueryClient);

  private PropositionAccess.Actor actor() {
    return new PropositionAccess.Actor(null, java.util.Set.of(), java.util.Set.of(), true, true);
  }

  private ProductProcessResponse emptyContent() {
    return new ProductProcessResponse("process", "plan", 1, 0L, null, null, null, null, null, false, null, null, null, null, null);
  }

  private PropositionEntity proposition() {
    var p = new PropositionEntity();
    p.setId(new ObjectId());
    p.setDefinition(new PropositionDefinition("Proposition", null, java.util.List.of(), null,
        java.util.List.of(), "USD", null, null, null, null, null, null, null, null, java.util.Set.of()));
    return p;
  }

  @Test void approvedByResolvesToUserDisplayNameViaCoreUserQueryClient() {
    var p = proposition();
    var plan = Plan.builder().version(1).status(PlanStatusEnum.APPROVED).regionIds(java.util.List.of()).tenantIds(java.util.List.of()).build();
    plan.setId(new ObjectId());
    String approver = new ObjectId().toHexString();
    plan.setApprovedBy(approver);
    plan.setApprovedAt(Instant.now());
    when(productProcesses.get(plan.getIdAsString())).thenReturn(emptyContent());
    when(userQueryClient.getById(approver)).thenReturn(
        UserQueryResponse.builder().firstName("An").lastName("Tran").build());
    var response = mapper.plan(p, plan, actor());
    assertThat(response.approvedBy()).isEqualTo("Tran An");
  }

  @Test void approvedByFallsBackToRawIdWhenUserCannotBeFound() {
    var p = proposition();
    var plan = Plan.builder().version(1).status(PlanStatusEnum.APPROVED).regionIds(java.util.List.of()).tenantIds(java.util.List.of()).build();
    plan.setId(new ObjectId());
    String approver = new ObjectId().toHexString();
    plan.setApprovedBy(approver);
    plan.setApprovedAt(Instant.now());
    when(productProcesses.get(plan.getIdAsString())).thenReturn(emptyContent());
    when(userQueryClient.getById(approver)).thenThrow(new IllegalArgumentException("User not found: " + approver));
    var response = mapper.plan(p, plan, actor());
    assertThat(response.approvedBy()).isEqualTo(approver);
  }
}
