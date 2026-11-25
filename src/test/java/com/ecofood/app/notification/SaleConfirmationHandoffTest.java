package com.ecofood.app.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.notification.service.*;
import com.ecofood.app.notification.config.NotificationSettings;
import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.submission.entity.InvoiceBatchSubmission;
import com.ecofood.app.transaction.dto.BatchTransactionDtos.*;
import com.ecofood.app.userACL.entity.*;
import java.time.Instant;
import java.util.List;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;

class SaleConfirmationHandoffTest {
  private final MongoTemplate mongo=mock(MongoTemplate.class);
  private final NotificationDirectory directory=mock(NotificationDirectory.class);
  private final NotificationSettings settings=new NotificationSettings("no-reply@example.org",true,5,"");
  private final SaleConfirmationHandoff handoff=new SaleConfirmationHandoff(mongo,directory,settings);
  private final String userId=new ObjectId().toHexString();
  private InvoiceBatchSubmission batch() { return InvoiceBatchSubmission.create("batch",Instant.now(),"tenant",List.of("i1","i2"),"hash",userId,
      List.of(new ReceiptResult(List.of(),"ACCEPTED",null),new ReceiptResult(List.of(),"ACCEPTED",null))); }
  private UserEntity seller() {
    var user=new UserEntity();user.setId(new ObjectId(userId));user.setEmail("seller@example.org");
    user.setUserCategory(UserCategory.builder().userCategoryCode("PARTNER").build());
    when(directory.user(userId,"tenant")).thenReturn(user); return user;
  }
  @Test void oneBatchQueuesOneEmailForVerifiedDirectorySellerNotAnEmailFromPayload() {
    seller(); handoff.deliver(batch());var captor=ArgumentCaptor.forClass(Notification.class); verify(mongo).insert(captor.capture());
    var notice=captor.getValue();assertEquals("seller@example.org",notice.getSendTo());assertEquals("batch",notice.getSubmissionId());
    assertTrue(notice.getContent().contains("2 hóa đơn"));assertEquals("PENDING",notice.getStatus());
    verify(mongo).updateFirst(any(Query.class),argThat((Update u)->Boolean.FALSE.equals(u.getUpdateObject().get("$set",org.bson.Document.class).get("sellerConfirmation.pending"))),eq(InvoiceBatchSubmission.class));
  }
  @Test void missingSellerOrCustomerIdentityCannotReceiveSellingConfirmation() {
    handoff.deliver(batch());verify(mongo,never()).insert(any(Notification.class));
    var user=seller();user.setUserCategory(UserCategory.builder().userCategoryCode("CUSTOMER").build());handoff.deliver(batch());
    verify(mongo,never()).insert(any(Notification.class));
  }
  @Test void outageKeepsDurableRetryAndDoesNotUndoSubmission() {
    seller();when(mongo.insert(any(Notification.class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("queue down"));
    assertDoesNotThrow(()->handoff.deliver(batch()));
    verify(mongo).updateFirst(any(Query.class),argThat((Update u)->u.getUpdateObject().containsKey("$inc")),eq(InvoiceBatchSubmission.class));
    verify(mongo,never()).remove(any(Query.class),eq(InvoiceBatchSubmission.class));
  }
  @Test void retryUsesDeterministicDeliveryIdAndDoesNotReplaceAlreadySentEmail() {
    seller();var existing=new Notification();existing.setId("batch:SELLER_CONFIRMATION");existing.setTenantId("tenant");existing.setSubmissionId("batch");
    existing.setType(SaleConfirmationHandoff.TYPE);existing.setRecipientUserId(userId);existing.setStatus("SENT");
    when(mongo.insert(any(Notification.class))).thenThrow(new DuplicateKeyException("exists"));
    when(mongo.findById(existing.getId(),Notification.class)).thenReturn(existing);
    handoff.deliver(batch());
    verify(mongo).updateFirst(any(Query.class),argThat((Update u)->Boolean.FALSE.equals(u.getUpdateObject().get("$set",org.bson.Document.class).get("sellerConfirmation.pending"))),eq(InvoiceBatchSubmission.class));
    assertEquals("SENT",existing.getStatus());
  }
  @Test void deliveryRevalidatesSellerMembershipEmailAndBatchSource() {
    var user=seller();var event=batch();var notice=new Notification();notice.setType(SaleConfirmationHandoff.TYPE);notice.setTenantId("tenant");
    notice.setSubmissionId(event.id());notice.setRecipientUserId(userId);notice.setSendTo(user.getEmail());
    when(directory.sourceBatch(notice)).thenReturn(event);
    var recipients=new NotificationRecipients(directory,settings);assertTrue(recipients.stillAllowed(notice));
    when(directory.user(userId,"tenant")).thenReturn(null);assertFalse(recipients.stillAllowed(notice));
  }
}
