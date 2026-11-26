package com.ecofood.app.dashboard.repository;
import com.ecofood.app.dashboard.security.DashboardAccess;
import com.ecofood.core.client.CoreDashboardPropositionClient;
import com.ecofood.core.dto.DashboardProposition;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
@Repository @RequiredArgsConstructor
public class DashboardData {
  private final CoreDashboardPropositionClient propositions;
  public List<DashboardProposition> propositions(com.ecofood.core.dto.DashboardActor actor) {
    return actor.tenantScope().isEmpty() ? List.of() : propositions.find(actor.tenant().id(), actor.entityIds());
  }
}
