package com.ecofood.app.notification;

import static com.ecofood.app.notification.NotificationFixtures.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.notification.controller.NotificationController;
import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.entity.NotificationRule;
import com.ecofood.app.notification.exception.NotificationApiErrors;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.notification.security.NotificationAccess;
import com.ecofood.app.notification.service.NotificationManagementImpl;
import com.ecofood.app.notification.service.NotificationRecipients;
import com.ecofood.app.notification.service.NotificationTemplate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Authentication filter is covered by existing auth tests; exercise feature authorization here. */
class NotificationApiTest {
  MongoTemplate mongo;
  NotificationAccess access;
  MockMvc mvc;
  @BeforeEach void setup() {
    mongo = mock(MongoTemplate.class); access = mock(NotificationAccess.class);
    when(access.current()).thenReturn(new NotificationAccess.Scope(TENANT, USER, true, true, true));
    var service = new NotificationManagementImpl(mongo, access, mock(NotificationRecipients.class), mock(NotificationDirectory.class), settings(), new NotificationTemplate());
    mvc = MockMvcBuilders.standaloneSetup(new NotificationController(service)).setControllerAdvice(new NotificationApiErrors(), new GlobalExceptionHandler()).build();
  }
  @Test void historyUsesScopeNotTenantHeaderAndCannotAcceptSentFromFrontend() throws Exception {
    when(mongo.find(any(Query.class), eq(Notification.class))).thenReturn(List.of());
    mvc.perform(get("/api/v1/notifications").header("X-Tenant-Id", OTHER)).andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
    verify(mongo).find(argThat((Query q) -> TENANT.equals(q.getQueryObject().getString("tenantId"))), eq(Notification.class));
    mvc.perform(put("/api/v1/notifications/delivery").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SENT\"}")).andExpect(status().isMethodNotAllowed());
  }
  @Test void manageAndReadAreSeparateAndPermissionCheckedBeforeMongoAccess() throws Exception {
    when(access.current()).thenReturn(new NotificationAccess.Scope(TENANT, USER, false, true, false));
    mvc.perform(get("/api/v1/notifications/rules")).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/notifications/delivery/retry").contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgeDuplicateRisk\":false}")).andExpect(status().isForbidden());
    verifyNoInteractions(mongo);
  }
  @Test void crossTenantRuleIsNotFoundAndNoWriteOccurs() throws Exception {
    mvc.perform(put("/api/v1/notifications/rules/another-tenant-rule/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"revision\":1}")).andExpect(status().isNotFound());
    verify(mongo).findOne(argThat((Query q) -> TENANT.equals(q.getQueryObject().getString("tenantId"))), eq(NotificationRule.class));
    verifyNoMoreInteractions(mongo);
  }
  @Test void createIgnoresSpoofedTenantAndAlwaysCreatesEmailRule() throws Exception {
    when(mongo.insert(any(NotificationRule.class))).thenAnswer(a -> a.getArgument(0));
    mvc.perform(post("/api/v1/notifications/rules").contentType(MediaType.APPLICATION_JSON).content("""
        {"tenantId":"spoofed", "name":"Receipt", "eventType":"INVOICE_PAID", "enabled":false,
         "recipients":{"userIds":[],"roles":[],"permissions":[],"emails":["approved@example.org"],"invoiceCustomer":false},
         "subject":"Invoice {invoiceNumber}", "content":"Recorded", "channel":"SMS"}
        """)).andExpect(status().isCreated()).andExpect(jsonPath("$.tenantId").value(TENANT)).andExpect(jsonPath("$.channel").value("EMAIL"));
  }
  @Test void invalidPlaceholderRejectedAndStaleUpdateDoesNotOverwriteRule() throws Exception {
    mvc.perform(post("/api/v1/notifications/rules").contentType(MediaType.APPLICATION_JSON).content("""
        {"name":"Rule","eventType":"INVOICE_PAID","enabled":false,"subject":"{profit}","content":"body"}
        """)).andExpect(status().isBadRequest());
    verifyNoInteractions(mongo);
    when(mongo.findOne(any(Query.class), eq(NotificationRule.class))).thenReturn(rule());
    mvc.perform(put("/api/v1/notifications/rules/rule/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"revision\":1}")).andExpect(status().isConflict());
  }
  @Test void malformedPayloadAndInvalidDateAreBadRequestsWithoutRawInputInResponse() throws Exception {
    mvc.perform(post("/api/v1/notifications/rules").contentType(MediaType.APPLICATION_JSON).content("{bad-json"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Payload hoặc tham số Notification không hợp lệ"));
    mvc.perform(get("/api/v1/notifications").param("from", "not-an-instant")).andExpect(status().isBadRequest());
    verifyNoInteractions(mongo);
  }
}
