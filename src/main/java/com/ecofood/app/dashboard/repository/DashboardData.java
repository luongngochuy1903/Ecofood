package com.ecofood.app.dashboard.repository;

import com.ecofood.core.client.CoreDashboardPropositionClient;
import com.ecofood.core.dto.DashboardActor;
import com.ecofood.core.dto.DashboardProposition;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** Reads Proposition dashboard data through the owning module's Core client. */
@Repository
@RequiredArgsConstructor
public class DashboardData {
  private final CoreDashboardPropositionClient propositions;

  public List<DashboardProposition> propositions(DashboardActor actor) {
    return actor.tenantScope().isEmpty()
        ? List.of()
        : propositions.find(actor.tenant().id(), actor.entityIds());
  }
}
