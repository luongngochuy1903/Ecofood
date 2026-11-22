package com.ecofood.app.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.orchestrator.service.BatchSalePlanResolver;
import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
import com.ecofood.app.orchestrator.repository.PlanSaleOrchestratorRepository;
import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.transaction.service.InvoiceSnapshotFactory;
import com.ecofood.app.transaction.entity.InvoiceSnapshot;
import com.ecofood.app.transaction.dto.BatchTransactionDtos.Item;
import com.ecofood.app.proposition.entity.Plan;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.PlanSaleOrchestratorStatusEnum;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BatchSalePlanResolverTest {
  private final PlanSaleOrchestratorRepository repository=mock(PlanSaleOrchestratorRepository.class);
  private final PlanSaleAccess access=mock(PlanSaleAccess.class);
  private final InvoiceSnapshotFactory snapshots=mock(InvoiceSnapshotFactory.class);
  private final BatchSalePlanResolver resolver=new BatchSalePlanResolver(repository,access,snapshots);
  private Item item(String plan){return new Item("1","orch",plan,2,BigDecimal.TEN,new BigDecimal("20"));}
  private void active() {
    var row=new PlanSaleOrchestrator();row.setId("orch");row.setTenantId("tenant");row.setPropositionId("prop");row.setPlanId("plan");row.setActiveVersion(3);
    row.setVersions(List.of(new PlanSaleOrchestratorDtos.ConfigVersion(3,PlanSaleOrchestratorStatusEnum.ACTIVE,
        new PlanSaleOrchestratorDtos.Configuration("Sale","",100,null,Set.of()),null,null,null,null,null,null)));
    when(repository.findByIdAndTenantId("orch","tenant")).thenReturn(Optional.of(row));
  }
  @Test void usesExactActiveVersionAndExistingSnapshotFactoryWithoutInventingSaleTime() {
    active();var plan=new Plan();plan.setName("Plan name");var scope=new PlanSaleAccess.Scope("tenant",null,null,plan);
    when(access.require("tenant","prop","plan")).thenReturn(scope);when(access.currency(scope)).thenReturn("VND");
    var snapshot=mock(InvoiceSnapshot.class);when(snapshots.capture(eq("tenant"),any(),any())).thenReturn(snapshot);
    var now=Instant.now();var line=resolver.resolve("tenant","seller","INV",item("plan"),now);
    assertEquals(snapshot,line.snapshot());assertEquals(2,line.quantity());assertFalse(line.saleConfigurationVerified());
    var captured=ArgumentCaptor.forClass(PreparedSaleTransaction.class);verify(snapshots).capture(eq("tenant"),captured.capture(),eq(now));
    assertEquals(3,captured.getValue().orchestratorVersion());assertEquals("plan",captured.getValue().binding().planId());
    assertEquals("VND",captured.getValue().transaction().currency());assertNull(captured.getValue().transaction().transactionTime());
    verify(access).active(scope);
  }
  @Test void tenantAndPlanMismatchCannotUseAnotherOrchestrator() {
    assertThrows(com.ecofood.app.config.ForbiddenException.class,()->resolver.resolve("other","seller","INV",item("plan"),Instant.now()));
    active();assertThrows(com.ecofood.app.config.BadRequestException.class,()->resolver.resolve("tenant","seller","INV",item("wrong"),Instant.now()));
    verifyNoInteractions(snapshots);
  }
}
