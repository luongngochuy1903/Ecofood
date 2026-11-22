package com.ecofood.app.notification;

import static com.ecofood.app.notification.NotificationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.notification.config.NotificationSettings;
import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.entity.NotificationRule;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.notification.security.NotificationAccess;
import com.ecofood.app.notification.service.NotificationManagementImpl;
import com.ecofood.app.notification.service.NotificationProcessor;
import com.ecofood.app.notification.service.NotificationRecipients;
import com.ecofood.app.notification.service.NotificationTemplate;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.entity.UserCategory;
import com.ecofood.core.dto.NotificationDtos;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.UserAccessService.UserAccessClaims;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

class NotificationSecurityTest {
  @Test void allAdminRolesCanUseNotificationWithMatchingLiveRoleAndTenantPermissions() {
    var directory=mock(NotificationDirectory.class); var user=user();
    when(directory.user(USER,TENANT)).thenReturn(user);
    var permissions=List.of(NotificationAccess.READ,NotificationAccess.MANAGE,NotificationAccess.RETRY);
    try(var token=mockStatic(AuthenticationContext.class)) {
      token.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(TENANT));
      token.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(USER));
      token.when(AuthenticationContext::getPermissions).thenReturn(permissions);
      for(String role:List.of("TENANT_ADMIN","MASTER_ADMIN","ENTERPRISE_ADMIN","ECO_ADMIN")) {
        when(directory.claims(user,TENANT)).thenReturn(new UserAccessClaims(List.of(role),permissions,List.of()));
        token.when(AuthenticationContext::getRoles).thenReturn(List.of(role));
        var scope=new NotificationAccess(directory).current();
        assertTrue(scope.read()); assertTrue(scope.manage()); assertTrue(scope.retry()); assertEquals(TENANT,scope.tenant());
      }
    }
  }
  @Test void customerEmailIsOnlyEligibleAfterInvoiceOwnershipAndRevalidatedOnDelivery() {
    var directory = mock(NotificationDirectory.class); var user = user(); user.setUserCategory(UserCategory.builder().userCategoryCode("CUSTOMER").build());
    when(directory.user(USER, TENANT)).thenReturn(user); var resolver = new NotificationRecipients(directory, settings()); var invoice = invoice();
    assertTrue(resolver.resolve(rule(), invoice).isEmpty());
    invoice.setInvoiceHolder(new com.ecofood.app.transaction.entity.Invoice.InvoiceHolder(USER, USER));
    assertEquals(1, resolver.resolve(rule(), invoice).size());
    var notice = delivery(); notice.setRecipientUserId(USER); assertFalse(resolver.stillAllowed(notice));
    when(directory.sourceInvoice(notice)).thenReturn(invoice); assertTrue(resolver.stillAllowed(notice));
  }
  @Test void ecoAdminAllowedOnlyWithMatchingLiveRoleAndPermissionsInCurrentTenant() {
    var directory = mock(NotificationDirectory.class); var user = user();
    when(directory.user(USER, TENANT)).thenReturn(user);
    var permissions = List.of(NotificationAccess.READ, NotificationAccess.MANAGE, NotificationAccess.RETRY);
    when(directory.claims(user, TENANT)).thenReturn(new UserAccessClaims(List.of("ECO_ADMIN"), permissions, List.of()));
    try (var token = mockStatic(AuthenticationContext.class)) {
      token.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(TENANT));
      token.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(USER));
      token.when(AuthenticationContext::getRoles).thenReturn(List.of("ECO_ADMIN"));
      token.when(AuthenticationContext::getPermissions).thenReturn(permissions);
      var scope = new NotificationAccess(directory).current();
      assertTrue(scope.manage()); assertTrue(scope.read()); assertTrue(scope.retry()); assertEquals(TENANT, scope.tenant());
      token.when(AuthenticationContext::getPermissions).thenReturn(List.of());
      var noPermissions = new NotificationAccess(directory).current();
      assertFalse(noPermissions.manage()); assertFalse(noPermissions.read()); assertFalse(noPermissions.retry());
      token.when(AuthenticationContext::getPermissions).thenReturn(permissions);
      when(directory.claims(user, TENANT)).thenReturn(new UserAccessClaims(List.of("ECO_ADMIN"), List.of(), List.of()));
      assertFalse(new NotificationAccess(directory).current().manage());
      when(directory.claims(user, TENANT)).thenReturn(new UserAccessClaims(List.of("TENANT_ADMIN"), permissions, List.of()));
      var notificationAccess1 = new NotificationAccess(directory);
      assertThrows(ResponseStatusException.class, () -> notificationAccess1.current());
      when(directory.user(USER, TENANT)).thenReturn(null);
      var notificationAccess2 = new NotificationAccess(directory);
      assertThrows(ResponseStatusException.class, () -> notificationAccess2.current());
    }
  }
  @Test void requiresLiveTenantAdminAndExplicitPermissions() {
    var directory = mock(NotificationDirectory.class); var user = user();
    when(directory.user(USER, TENANT)).thenReturn(user);
    when(directory.claims(user, TENANT)).thenReturn(new UserAccessClaims(List.of("TENANT_ADMIN"), List.of(NotificationAccess.READ), List.of()));
    try (var token = mockStatic(AuthenticationContext.class)) {
      token.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(TENANT)); token.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(USER));
      token.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_ADMIN"));
      token.when(AuthenticationContext::getPermissions).thenReturn(List.of(NotificationAccess.READ, NotificationAccess.MANAGE));
      var scope = new NotificationAccess(directory).current(); assertTrue(scope.read()); assertFalse(scope.manage());
      assertThrows(ResponseStatusException.class, scope::requireManage);
      when(directory.claims(user, TENANT)).thenReturn(new UserAccessClaims(List.of("TENANT_EMPLOYEE"), List.of(NotificationAccess.READ), List.of()));
      var notificationAccess3 = new NotificationAccess(directory);
      assertThrows(ResponseStatusException.class, () -> notificationAccess3.current());
    }
  }
  @Test void tokenCannotGrantAccessToAnotherTenantOrRevokedMembership() {
    var directory = mock(NotificationDirectory.class);
    try (var token = mockStatic(AuthenticationContext.class)) {
      token.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(OTHER)); token.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(USER));
      var notificationAccess4 = new NotificationAccess(directory);
      assertThrows(ResponseStatusException.class, () -> notificationAccess4.current());
      verify(directory).user(USER, OTHER); verify(directory, never()).claims(any(), any());
    }
  }
  @Test void recipientMustBeUserInExactTenantAndNotDeleted() {
    var user = user(); assertTrue(NotificationDirectory.belongs(user, TENANT)); assertFalse(NotificationDirectory.belongs(user, OTHER));
    user.setDeleted(true); assertFalse(NotificationDirectory.belongs(user, TENANT));
  }
  @Test void ruleCannotReferenceOutOfScopeUserOrUnapprovedEmail() {
    var directory = mock(NotificationDirectory.class); var resolver = new NotificationRecipients(directory, settings());
    var recipients1 = rule().getRecipients();
    assertThrows(ResponseStatusException.class, () -> resolver.validate(recipients1, OTHER));
    var recipient = new NotificationDtos.Recipients(List.of(), List.of(), List.of(), false, List.of("approved@example.org"));
    resolver.validate(recipient, TENANT); assertThrows(ResponseStatusException.class, () -> resolver.validate(recipient, OTHER));
    var recipients2 = new NotificationDtos.Recipients(List.of(), List.of(), List.of(), false, List.of("attacker@example.org"));
    assertThrows(ResponseStatusException.class, () -> resolver.validate(recipients2, TENANT));
  }
  @Test void missingEmailSkippedAndNeverUsesSubmittedBy() {
    var directory = mock(NotificationDirectory.class); var user = user(); user.setEmail(null); when(directory.user(USER, TENANT)).thenReturn(user);
    var invoice = invoice(); invoice.setSubmittedBy(USER); assertTrue(new NotificationRecipients(directory, settings()).resolve(rule(), invoice).isEmpty());
  }
  @Test void invoiceCustomerOnlyUsesVerifiedTenantCustomerUser() {
    var directory = mock(NotificationDirectory.class); var resolver = new NotificationRecipients(directory, settings());
    var rule = rule(); rule.setRecipients(new NotificationDtos.Recipients(List.of(), List.of(), List.of(), true, List.of()));
    var invoice = invoice(); invoice.setInvoiceHolder(new com.ecofood.app.transaction.entity.Invoice.InvoiceHolder("unverified", null));
    assertTrue(resolver.resolve(rule, invoice).isEmpty());
    var user = user(); user.setUserCategory(UserCategory.builder().userCategoryCode("CUSTOMER").build()); when(directory.user(USER, TENANT)).thenReturn(user);
    invoice.setInvoiceHolder(new com.ecofood.app.transaction.entity.Invoice.InvoiceHolder(null, USER));
    assertEquals("customer@example.org", resolver.resolve(rule, invoice).get(0).email());
  }
  @Test void tenantRoleRecipientsResolveLiveAndDuplicateEmailIsMerged() {
    var directory = mock(NotificationDirectory.class); var user = user(); when(directory.user(USER, TENANT)).thenReturn(user); when(directory.users(TENANT)).thenReturn(List.of(user));
    when(directory.claims(user, TENANT)).thenReturn(new UserAccessClaims(List.of("TENANT_ADMIN"), List.of(), List.of()));
    var rule = rule(); rule.setRecipients(new NotificationDtos.Recipients(List.of(USER), List.of("TENANT_ADMIN"), List.of(), false, List.of()));
    assertEquals(1, new NotificationRecipients(directory, settings()).resolve(rule, invoice()).size());
  }
  @Test void templateOnlyAcceptsSupportedPlaceholdersAndRemovesHeaderNewlines() {
    var template = new NotificationTemplate(); template.validate("Invoice {invoiceNumber}", "Amount {totalAmount}");
    assertThrows(ResponseStatusException.class, () -> template.validate("Subject\r\nBcc:other", "body"));
    assertThrows(ResponseStatusException.class, () -> template.validate("Subject {profit}", "body"));
    assertThrows(ResponseStatusException.class, () -> template.validate("Subject", "Malformed {customer.email}"));
    assertEquals("Invoice a  Bcc:other", template.render("Invoice {invoiceNumber}", Map.of("invoiceNumber", "a\r\nBcc:other"), true));
    Map<String, String> values = Map.of();
    assertThrows(IllegalStateException.class, () -> template.render("Amount {totalAmount}", values, false));
  }
  @Test void notificationIdIncludesRuleAndCanonicalRecipient() {
    assertNotEquals(NotificationProcessor.deliveryId("event", "rule", "a@x.com"), NotificationProcessor.deliveryId("event", "rule2", "a@x.com"));
    assertEquals(NotificationProcessor.deliveryId("event", "rule", "a@x.com"), NotificationProcessor.deliveryId("event", "rule", "a@x.com"));
  }
  @Test void emailValidationRejectsInjectedHeaderAndLists() {
    assertFalse(NotificationSettings.validEmail("one@example.org\r\nBcc:two@example.org"));
    assertFalse(NotificationSettings.validEmail("one@example.org,two@example.org"));
    assertTrue(settings().approved(OTHER).isEmpty());
  }
  @Test void apiQueriesAreTenantScopedAndManualRetryCannotRetrySent() {
    var mongo = mock(MongoTemplate.class); var access = mock(NotificationAccess.class);
    when(access.current()).thenReturn(new NotificationAccess.Scope(TENANT, USER, true, true, true));
    var service = new NotificationManagementImpl(mongo, access, mock(NotificationRecipients.class), mock(NotificationDirectory.class), settings(), new NotificationTemplate());
    var sent = delivery(); sent.setStatus("SENT"); when(mongo.findOne(any(Query.class), eq(Notification.class))).thenReturn(sent);
    var retryRequest1 = new NotificationDtos.RetryRequest(false);
    assertThrows(ResponseStatusException.class, () -> service.retry("delivery", retryRequest1));
    var query = org.mockito.ArgumentCaptor.forClass(Query.class); verify(mongo).findOne(query.capture(), eq(Notification.class)); assertEquals(TENANT, query.getValue().getQueryObject().getString("tenantId"));
    when(mongo.findOne(any(Query.class), eq(NotificationRule.class))).thenReturn(null);
    var enabledRequest1 = new NotificationDtos.EnabledRequest(true, 1);
    assertThrows(ResponseStatusException.class, () -> service.enable("other-tenant-rule", enabledRequest1));
    verify(mongo, never()).findAndModify(any(), any(), any(), eq(NotificationRule.class));
  }
  @Test void ambiguousDeliveryRequiresExplicitAcknowledgment() {
    var mongo = mock(MongoTemplate.class); var access = mock(NotificationAccess.class);
    when(access.current()).thenReturn(new NotificationAccess.Scope(TENANT, USER, true, true, true));
    var failed = delivery(); failed.setStatus("FAILED"); failed.setDeliveryUncertain(true);
    when(mongo.findOne(any(Query.class), eq(Notification.class))).thenReturn(failed);
    var service = new NotificationManagementImpl(mongo, access, mock(NotificationRecipients.class), mock(NotificationDirectory.class), settings(), new NotificationTemplate());
    var retryRequest2 = new NotificationDtos.RetryRequest(false);
    assertThrows(ResponseStatusException.class, () -> service.retry("delivery", retryRequest2));
    verify(mongo, never()).findAndModify(any(), any(), any(), eq(Notification.class));
  }
}
