package com.ecofood.app.audit.service;

import com.ecofood.app.audit.entity.AuditLogEntity;
import com.ecofood.app.audit.repository.AuditLogRepository;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.core.dto.AuditLogDtos.*;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.AuditLogService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {
  private static final Set<String> ADMINS = Set.of("TENANT_ADMIN", "MASTER_ADMIN", "ENTERPRISE_ADMIN", "ECO_ADMIN");
  private final AuditLogRepository repository;
  private final NotificationDirectory directory;

  private String currentTenant() {
    String tenant = AuthenticationContext.getTenantId().toHexString();
    var user = directory.user(AuthenticationContext.getUserId().toHexString(), tenant);
    if (user != null) {
      var live = directory.claims(user, tenant);
      var roles = AuthenticationContext.getRoles();
      var permissions = AuthenticationContext.getPermissions();
      if (roles != null && permissions != null && permissions.contains("AUDIT_LOG_READ")
          && live.permissions().contains("AUDIT_LOG_READ")
          && ADMINS.stream().anyMatch(role -> live.roles().contains(role) && roles.contains(role))) return tenant;
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied.");
  }

  public void record(Event event) {
    repository.insert(new AuditLogEntity(null, event.tenantId(), event.actorId(), event.action(),
        event.controller(), event.timestamp(), event.outcome(), event.requestId()));
  }

  public Page list(Filter filter) {
    String tenant = currentTenant();
    if (filter.page() < 0 || filter.size() < 1 || filter.size() > 100 ||
        (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filters.");
    var rows = repository.list(tenant, filter);
    return new Page(rows.stream().limit(filter.size()).map(this::entry).toList(), rows.size() > filter.size());
  }

  public Entry detail(String id) {
    var row = repository.detail(currentTenant(), id);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return entry(row);
  }

  public List<String> actors(String prefix) {
    String tenant = currentTenant();
    if (prefix.length() > 24) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return repository.actors(tenant, prefix);
  }

  private Entry entry(AuditLogEntity row) {
    String module = row.controller().replaceFirst("Controller$", "");
    return new Entry(row.id(), row.actorId(), row.action(), module, row.timestamp(), row.outcome(), row.requestId());
  }
}
