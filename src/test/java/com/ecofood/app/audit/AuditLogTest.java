package com.ecofood.app.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.audit.entity.AuditLogEntity;
import com.ecofood.app.audit.repository.AuditLogRepository;
import com.ecofood.app.audit.service.AuditLogServiceImpl;
import com.ecofood.app.complaint.controller.ComplaintController;
import com.ecofood.app.config.AuditInterceptor;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.core.dto.AuditLogDtos.*;
import com.ecofood.core.dto.ComplaintDtos;
import com.ecofood.core.enums.*;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.AuditLogService;
import com.ecofood.core.service.UserAccessService.UserAccessClaims;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;

class AuditLogTest {
  @Test void adminReadsOnlySessionTenantAndCannotOpenChildRecord() {
    var directory = mock(NotificationDirectory.class);
    var repository = mock(AuditLogRepository.class);
    var service = new AuditLogServiceImpl(repository, directory);
    var tenant = new ObjectId();
    var actor = new ObjectId();
    var user = mock(UserEntity.class);
    var permission = List.of("AUDIT_LOG_READ");
    var filter = new Filter("employee", null, null, null, null, null, 0, 25);
    when(directory.user(actor.toHexString(), tenant.toHexString())).thenReturn(user);
    try (var ctx = mockStatic(AuthenticationContext.class)) {
      ctx.when(AuthenticationContext::getTenantId).thenReturn(tenant);
      ctx.when(AuthenticationContext::getUserId).thenReturn(actor);
      ctx.when(AuthenticationContext::getPermissions).thenReturn(permission);
      for (String role : List.of("TENANT_ADMIN","MASTER_ADMIN","ENTERPRISE_ADMIN","ECO_ADMIN")) {
        ctx.when(AuthenticationContext::getRoles).thenReturn(List.of(role));
        when(directory.claims(user,tenant.toHexString())).thenReturn(new UserAccessClaims(List.of(role),permission,List.of()));
        service.list(filter);
        service.actors("abc");
        assertEquals(404,assertThrows(ResponseStatusException.class, () -> service.detail("child-id")).getStatusCode().value());
      }
      verify(repository,times(4)).list(tenant.toHexString(),filter);
      verify(repository,times(4)).actors(tenant.toHexString(),"abc");
      verify(repository,times(4)).detail(tenant.toHexString(),"child-id");
      for (String role : List.of("TENANT_EMPLOYEE", "CUSTOMER")) {
        ctx.when(AuthenticationContext::getRoles).thenReturn(List.of(role));
        when(directory.claims(user,tenant.toHexString())).thenReturn(new UserAccessClaims(List.of(role),permission,List.of()));
        assertEquals(403,assertThrows(ResponseStatusException.class, () -> service.list(filter)).getStatusCode().value());
      }
    }
  }

  @Test void repositoryBindsTenantForBothHistoryAndDetail() {
    var mongo = mock(MongoTemplate.class);
    var repo = new AuditLogRepository(mongo);
    repo.detail("A","B-record");
    repo.list("A",new Filter("user",null,null,null,null,null,0,25));
    var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).findOne(query.capture(),eq(AuditLogEntity.class));
    assertEquals("A",query.getValue().getQueryObject().get("tenantId"));
    assertEquals("B-record",query.getValue().getQueryObject().get("_id"));
    verify(mongo).find(query.capture(),eq(AuditLogEntity.class));
    assertEquals("A",query.getValue().getQueryObject().get("tenantId"));
    assertEquals("user",query.getValue().getQueryObject().get("actorId"));
  }

  @Test void complaintCreationAndAcceptanceCarryVerifiedIdentityOutcomeAndCorrelation() throws Exception {
    var audit = mock(AuditLogService.class);
    var interceptor = new AuditInterceptor(audit);
    var controller = mock(ComplaintController.class);
    var create = new HandlerMethod(controller,ComplaintController.class.getMethod("create",ComplaintDtos.Create.class));
    var accept = new HandlerMethod(controller,ComplaintController.class.getMethod("action",String.class,String.class,ComplaintDtos.Action.class));
    try (var actor = MDC.putCloseable("actorId","user"); var tenant = MDC.putCloseable("tenantId","B");
         var correlation = MDC.putCloseable("requestId","trace")) {
      var response = new MockHttpServletResponse();
      interceptor.afterCompletion(new MockHttpServletRequest(),response,create,null);
      interceptor.afterCompletion(new MockHttpServletRequest(),response,accept,null);
      response.setStatus(409);
      interceptor.afterCompletion(new MockHttpServletRequest(),response,accept,null);
    }
    var captured = ArgumentCaptor.forClass(Event.class);
    verify(audit,times(3)).record(captured.capture());
    var events = captured.getAllValues();
    assertEquals(List.of(AuditAction.CREATE,AuditAction.UPDATE,AuditAction.UPDATE),events.stream().map(Event::action).toList());
    assertEquals(List.of(AuditOutcome.SUCCESS,AuditOutcome.SUCCESS,AuditOutcome.FAILURE),events.stream().map(Event::outcome).toList());
    assertTrue(events.stream().allMatch(e -> "B".equals(e.tenantId()) && "trace".equals(e.requestId())
        && "ComplaintController".equals(e.controller()) && "user".equals(e.actorId())));
  }
}
