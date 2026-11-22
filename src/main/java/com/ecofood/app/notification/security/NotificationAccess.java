package com.ecofood.app.notification.security;

import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.core.security.AuthenticationContext;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** UC-016 only: Admin roles and effective permissions, with live tenant membership. */
@Service
@RequiredArgsConstructor
public class NotificationAccess {
  public static final String READ = "NOTIFICATION_READ", MANAGE = "NOTIFICATION_RULE_MANAGE", RETRY = "NOTIFICATION_RETRY";
  private final NotificationDirectory directory;
  public record Scope(String tenant, String actor, boolean manage, boolean read, boolean retry) {
    public void requireManage() { if (!manage) throw denied(); }
    public void requireRead() { if (!read) throw denied(); }
    public void requireRetry() { requireRead(); if (!retry) throw denied(); }
  }
  public Scope current() {
    try {
      String tenant = AuthenticationContext.getTenantId().toHexString();
      String actor = AuthenticationContext.getUserId().toHexString();
      var user = directory.user(actor, tenant);
      if (user == null) throw denied();
      var live = directory.claims(user, tenant);
      var tokenRoles = Optional.ofNullable(AuthenticationContext.getRoles()).orElse(List.of());
      boolean admin = List.of("TENANT_ADMIN", "MASTER_ADMIN", "ENTERPRISE_ADMIN", "ECO_ADMIN").stream()
          .anyMatch(role -> live.roles().contains(role) && tokenRoles.contains(role));
      if (!admin) throw denied();
      Set<String> permissions = new HashSet<>(live.permissions());
      permissions.retainAll(Optional.ofNullable(AuthenticationContext.getPermissions()).orElse(List.of()));
      return new Scope(tenant, actor, permissions.contains(MANAGE), permissions.contains(READ), permissions.contains(RETRY));
    } catch (ResponseStatusException e) { throw e; }
    catch (RuntimeException e) { throw denied(); }
  }
  static ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "Cần role Admin và permission Notification trong Tenant hiện tại"); }
}
