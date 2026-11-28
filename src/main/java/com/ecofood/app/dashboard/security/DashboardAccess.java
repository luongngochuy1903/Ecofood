package com.ecofood.app.dashboard.security;
import com.ecofood.core.client.CoreDashboardAccessClient;
import com.ecofood.core.dto.DashboardActor;
import com.ecofood.core.enums.AccessEntityTypeEnum;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
@Service @RequiredArgsConstructor
public class DashboardAccess {
  private final CoreDashboardAccessClient client;
  public DashboardActor current() { return client.current(); }
  public DashboardActor current(AccessEntityTypeEnum type, String tenant) { return client.current(type, tenant); }
  public List<DashboardActor> tenantOptions(AccessEntityTypeEnum type) { return client.tenantOptions(type); }
  public List<DashboardActor> authorizedTenants(AccessEntityTypeEnum type) { return client.authorizedTenants(type); }
  public List<DashboardActor> scopedTenants(AccessEntityTypeEnum type, String root) { return client.scopedTenants(type, root); }
  public List<com.ecofood.core.dto.DashboardDtos.Option> selectableTenants(AccessEntityTypeEnum type) { return client.selectableTenants(type); }
  public static ResponseStatusException denied() { return error(HttpStatus.FORBIDDEN, "Dashboard access denied"); }
  public static ResponseStatusException error(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
}
