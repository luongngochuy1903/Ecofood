package com.ecofood.app.notification.service;

import com.ecofood.app.notification.config.NotificationSettings;
import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.submission.entity.InvoiceBatchSubmission;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** One acknowledgement per shared Submission; existing Notification delivery owns SMTP and retries. */
@Service
@RequiredArgsConstructor
public class SaleConfirmationHandoff {
  public static final String TYPE = "SALE_BATCH_ACCEPTED";
  private static final String CONFIRMATION_PENDING = "sellerConfirmation.pending";
  private static final String CONFIRMATION_NEXT_RETRY_AT = "sellerConfirmation.nextRetryAt";
  private final MongoTemplate mongo;
  private final NotificationDirectory directory;
  private final NotificationSettings settings;

  @Scheduled(fixedDelayString="${ecofood.sales.confirmation-retry-ms:10000}",
      initialDelayString="${ecofood.sales.confirmation-initial-ms:20000}")
  public void recover() {
    try {
      var due = Query.query(Criteria.where("sourceType").is("INVOICE_BATCH")
          .and(CONFIRMATION_PENDING).is(true)
          .and(CONFIRMATION_NEXT_RETRY_AT).lte(Instant.now())).limit(100);
      for (var event : mongo.find(due, InvoiceBatchSubmission.class)) {
        deliver(event);
      }
    } catch (RuntimeException unavailable) {
      // Durable intent stays on the Submission.
    }
  }

  public void deliver(InvoiceBatchSubmission batch) {
    if (batch.sellerConfirmation() == null || !batch.sellerConfirmation().pending()
        || batch.invoiceIds().isEmpty()) return;
    var owned = Query.query(Criteria.where("_id").is(batch.id()).and("tenantId").is(batch.tenantId())
        .and(CONFIRMATION_PENDING).is(true));
    try {
      var notice = createNotice(batch);
      insertNoticeIdempotently(notice);
      mongo.updateFirst(owned, new Update().set(CONFIRMATION_PENDING, false)
          .unset(CONFIRMATION_NEXT_RETRY_AT).unset("sellerConfirmation.lastError"),
          InvoiceBatchSubmission.class);
    } catch (RuntimeException unavailable) {
      deferHandoff(owned);
    }
  }

  private Notification createNotice(InvoiceBatchSubmission batch) {
    var user = directory.user(batch.submittedBy(), batch.tenantId());
    if (user == null || user.getUserCategory() == null
        || !"PARTNER".equals(user.getUserCategory().getUserCategoryCode())
        || !NotificationSettings.validEmail(user.getEmail()) || !NotificationSettings.validEmail(settings.from)) {
      throw new IllegalStateException("CONFIRMATION_RECIPIENT_OR_SENDER_UNAVAILABLE");
    }
    var notice = new Notification();
    notice.setId(batch.id() + ":SELLER_CONFIRMATION");
    notice.setTenantId(batch.tenantId());
    notice.setSubmissionId(batch.id());
    notice.setRuleId("SYSTEM_SELLER_RECEIPT_CONFIRMATION");
    notice.setType(TYPE);
    notice.setRecipientUserId(user.getIdAsString());
    notice.setSendTo(NotificationSettings.canonical(user.getEmail()));
    notice.setSendFrom(settings.from);
    notice.setSubject("EcoFood: xác nhận tiếp nhận giao dịch");
    notice.setContent(confirmationContent(batch));
    notice.setCreatedAt(Instant.now());
    notice.setNextAttemptAt(Instant.now());
    return notice;
  }

  private String confirmationContent(InvoiceBatchSubmission batch) {
    long rejected = batch.results().stream().filter(r -> !"ACCEPTED".equals(r.result())).count();
    return "EcoFood đã tiếp nhận thành công " + batch.invoiceIds().size() + " hóa đơn"
        + ". Số hóa đơn bị từ chối: " + rejected
        + ". KPI được xử lý bất đồng bộ; email này không xác nhận KPI đã hoàn tất.";
  }

  private void insertNoticeIdempotently(Notification notice) {
    try {
      mongo.insert(notice);
    } catch (DuplicateKeyException duplicate) {
      validateExistingNotice(notice);
    }
  }

  private void validateExistingNotice(Notification notice) {
    var existing = mongo.findById(notice.getId(), Notification.class);
    if (existing == null || !Objects.equals(existing.getTenantId(), notice.getTenantId())
        || !Objects.equals(existing.getSubmissionId(), notice.getSubmissionId())
        || !Objects.equals(existing.getRecipientUserId(), notice.getRecipientUserId())
        || !TYPE.equals(existing.getType())) {
      throw new IllegalStateException("CONFIRMATION_CONFLICT");
    }
  }

  private void deferHandoff(Query owned) {
    try {
      mongo.updateFirst(owned, new Update().set(CONFIRMATION_NEXT_RETRY_AT, Instant.now().plusSeconds(60))
          .inc("sellerConfirmation.attempts", 1).set("sellerConfirmation.lastError", "CONFIRMATION_DEFERRED"),
          InvoiceBatchSubmission.class);
    } catch (RuntimeException ignored) {
      // Next poll retries the persisted intent.
    }
  }
}
