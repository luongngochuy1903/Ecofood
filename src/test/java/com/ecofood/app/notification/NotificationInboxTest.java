package com.ecofood.app.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.customer.auth.*;
import com.ecofood.app.customer.repository.*;
import com.ecofood.app.notification.NotificationContracts.*;
import com.ecofood.app.userACL.entity.*;
import com.mongodb.client.result.UpdateResult;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;

class NotificationInboxTest {
  CustomerIdentityService identities = mock(CustomerIdentityService.class);
  MongoTemplate mongo = mock(MongoTemplate.class);
  NotificationService service = new NotificationService(identities, mock(CustomerTenantResolver.class), mock(CustomerPurchaseRepository.class),
      mock(NotificationPreferenceService.class), mock(NotificationRepository.class), mongo,
      Clock.fixed(NotificationServiceTest.NOW, ZoneOffset.UTC), mock(com.ecofood.app.complaint.ComplaintNotificationSource.class));
  CustomerIdentityService.CustomerSession scope = new CustomerIdentityService.CustomerSession(NotificationServiceTest.CUSTOMER, NotificationServiceTest.PORTAL);
  Notification row;
  @BeforeEach void setup() {
    when(identities.requirePurchaseSession()).thenReturn(scope);
    var user = UserEntity.builder().partyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(scope.customerTenantId())).build())).build(); user.setId(new ObjectId(scope.customerId()));
    when(identities.requireSelfServiceAccount("CUSTOMER_NOTIFICATION_MANAGE")).thenReturn(user);
    row = new Notification(); row.setId(new ObjectId()); row.setRecipientCustomerId(scope.customerId()); row.setCustomerTenantId(scope.customerTenantId());
    row.setSourceTenantId(NotificationServiceTest.SELLER); row.setType(Type.SERVICE); row.setDeliveryChannel(Channel.IN_APP); row.setDeliveryStatus(DeliveryStatus.AVAILABLE);
  }
  @Test void listAndUnreadQueriesAlwaysContainRecipientPortalAndVisibleInAppStatus() {
    when(mongo.count(any(Query.class), eq(Notification.class))).thenReturn(21L);
    when(mongo.find(any(Query.class), eq(Notification.class))).thenReturn(List.of(row));
    var result = service.list(Type.SERVICE, false, 2, 10); assertEquals(21, result.getTotalElements()); assertEquals(3, result.getTotalPages());
    var query = ArgumentCaptor.forClass(Query.class); verify(mongo).find(query.capture(), eq(Notification.class));
    Document filter = query.getValue().getQueryObject();
    assertEquals(scope.customerId(), filter.get("recipientCustomerId")); assertEquals(scope.customerTenantId(), filter.get("customerTenantId"));
    assertEquals(Channel.IN_APP, filter.get("deliveryChannel")); assertEquals(false, filter.get("read")); assertEquals(Type.SERVICE, filter.get("type"));
    assertFalse(filter.containsKey("sourceTenantId")); // a Customer can receive legitimate messages from many sellers
    assertEquals(20, query.getValue().getSkip()); assertEquals(10, query.getValue().getLimit());
    assertEquals(21, service.unreadCount(null).unreadCount());
  }
  @Test void markingReadDoesNotAlterDeliveryAndRepeatPreservesOriginalReadTime() {
    row.setRead(true); row.setReadAt(NotificationServiceTest.NOW.minusSeconds(30));
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Notification.class))).thenReturn(null);
    when(mongo.findOne(any(Query.class), eq(Notification.class))).thenReturn(row);
    var result = service.markRead(row.getIdAsString(), true); assertEquals(row.getReadAt(), result.readAt()); assertEquals(DeliveryStatus.AVAILABLE, result.deliveryStatus());
    var update = ArgumentCaptor.forClass(Update.class); var query = ArgumentCaptor.forClass(Query.class);
    verify(mongo).findAndModify(query.capture(), update.capture(), any(FindAndModifyOptions.class), eq(Notification.class));
    assertEquals(new Document("$ne", true), query.getValue().getQueryObject().get("read"));
    assertFalse(update.getValue().getUpdateObject().get("$set", Document.class).containsKey("deliveryStatus"));
    assertEquals(scope.customerId(), query.getValue().getQueryObject().get("recipientCustomerId"));
  }
  @Test void markingUnreadClearsReadAtAndReadAllUsesRecipientScopedCutoff() {
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Notification.class))).thenReturn(row);
    service.markRead(row.getIdAsString(), false);
    var update = ArgumentCaptor.forClass(Update.class); verify(mongo).findAndModify(any(Query.class), update.capture(), any(FindAndModifyOptions.class), eq(Notification.class));
    assertTrue(update.getValue().getUpdateObject().get("$unset", Document.class).containsKey("readAt"));
    when(mongo.updateMulti(any(Query.class), any(Update.class), eq(Notification.class))).thenReturn(UpdateResult.acknowledged(2, 2L, null));
    assertEquals(2, service.readAll().modifiedCount());
    var query = ArgumentCaptor.forClass(Query.class); verify(mongo).updateMulti(query.capture(), any(Update.class), eq(Notification.class));
    assertEquals(scope.customerId(), query.getValue().getQueryObject().get("recipientCustomerId"));
    assertEquals(new Document("$lte", NotificationServiceTest.NOW), query.getValue().getQueryObject().get("createdAt"));
  }
  @Test void foreignOrMissingNotificationReturnsUnavailableWithoutAnyUnscopedLookup() {
    when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Notification.class))).thenReturn(null);
    when(mongo.findOne(any(Query.class), eq(Notification.class))).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () -> service.markRead(row.getIdAsString(), true));
    var query = ArgumentCaptor.forClass(Query.class); verify(mongo).findOne(query.capture(), eq(Notification.class));
    assertEquals(scope.customerId(), query.getValue().getQueryObject().get("recipientCustomerId"));
    assertEquals(scope.customerTenantId(), query.getValue().getQueryObject().get("customerTenantId"));
  }
  @Test void invalidPagingAndMissingPermissionAreRejectedBeforeDatabaseAccess() {
    assertThrows(com.ecofood.app.config.BadRequestException.class, () -> service.list(null, null, -1, 10));
    assertThrows(com.ecofood.app.config.BadRequestException.class, () -> service.list(null, null, 0, 101)); verifyNoInteractions(mongo);
    doThrow(new com.ecofood.app.config.ForbiddenException("Denied")).when(identities).requireSelfServiceAccount("CUSTOMER_NOTIFICATION_MANAGE");
    assertThrows(com.ecofood.app.config.ForbiddenException.class, () -> service.markRead(row.getIdAsString(), true)); verifyNoInteractions(mongo);
  }
  @Test void legacyFeedbackIsExcludedFromAllCustomerInboxOperations() {
    var query = service.inbox(scope, Type.COMPLAINT).getQueryObject();
    assertEquals(new Document("$ne", ReferenceType.FEEDBACK), query.get("referenceType"));
    assertEquals(Type.COMPLAINT, query.get("type")); assertEquals(scope.customerId(), query.get("recipientCustomerId"));
  }
}
