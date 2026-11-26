package com.ecofood.app.dashboard;
import static com.ecofood.app.dashboard.DashboardFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.ecofood.app.dashboard.repository.DashboardData;
import com.ecofood.app.dashboard.security.DashboardAccess;
import com.ecofood.app.dashboard.service.*;
import com.ecofood.core.client.CoreDashboardPropositionClient;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class DashboardServiceTest {
  @Test void missingRuleDoesNotReadPropositions() {
    var client = mock(CoreDashboardPropositionClient.class);
    var empty = new com.ecofood.core.dto.DashboardActor(USER, tenant(A,false),Set.of(),false,Set.of());
    assertTrue(new DashboardData(client).propositions(empty).isEmpty());
    verifyNoInteractions(client);
  }
  @Test void limitedIdsAreForwardedToOwningModule() {
    var client = mock(CoreDashboardPropositionClient.class);
    var actor = new com.ecofood.core.dto.DashboardActor(USER,tenant(A,false),Set.of(A),false,Set.of(PROP));
    when(client.find(A,Set.of(PROP))).thenReturn(List.of());
    new DashboardData(client).propositions(actor);
    verify(client).find(A,Set.of(PROP));
  }
  @Test void monthlyIsRejected() {
    var access = mock(DashboardAccess.class);
    when(access.current(AccessEntityTypeEnum.BUSINESS_DASHBOARD,A)).thenReturn(actor(false));
    var service = new DashboardServiceImpl(access,mock(DashboardData.class),new DashboardAggregation());
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.summary(selection(DashboardPeriodEnum.MONTHLY)));
  }
}
