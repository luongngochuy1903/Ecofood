package com.ecofood.app.notification;

import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.userACL.entity.UserEntity;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationRecipients {
  private final NotificationDirectory directory;
  private final NotificationSettings settings;
  private List<NotificationAudience> audiences = List.of();
  @org.springframework.beans.factory.annotation.Autowired
  public void setAudiences(List<NotificationAudience> values) { audiences = List.copyOf(values); }
  public void validateEvent(String event, NotificationRule.Recipients config, String tenant) {
    var audience = audiences.stream().filter(a -> a.supports(event)).findFirst();
    if (audience.isPresent()) audience.get().validate(event,config,tenant);
    else if (!"INVOICE_PAID".equals(event) || Boolean.TRUE.equals(config.complaintCustomer())) throw NotificationTemplate.bad("Unsupported event recipients");
  }
  public List<Recipient> resolve(NotificationRule rule, Invoice invoice, com.ecofood.app.submission.entity.Submission event) {
    return audiences.stream().filter(a -> a.supports(event.event())).findFirst().map(a -> a.resolve(rule,event))
        .orElseGet(() -> "INVOICE_PAID".equals(event.event()) ? resolve(rule,invoice) : List.of());
  }
  public record Recipient(String email, String userId) {}
  static List<String> safe(List<String> values) { return values == null ? List.of() : values; }
  public void validate(NotificationRule.Recipients recipients, String tenant) {
    if (recipients == null) throw NotificationTemplate.bad("Cần cấu hình người nhận");
    for (List<String> values : List.of(safe(recipients.userIds()), safe(recipients.roles()), safe(recipients.permissions()), safe(recipients.emails())))
      if (values.size() > 100 || values.stream().anyMatch(v -> v == null || v.isBlank() || v.length() > 254)) throw NotificationTemplate.bad("Danh sách người nhận không hợp lệ");
    for (String id : safe(recipients.userIds())) if (directory.user(id, tenant) == null) throw NotificationTemplate.bad("Người nhận không thuộc Tenant");
    for (String email : safe(recipients.emails())) if (!NotificationSettings.validEmail(email) || !settings.approved(tenant).contains(NotificationSettings.canonical(email)))
      throw NotificationTemplate.bad("Email chưa được phê duyệt cho Tenant");
    if (!recipients.invoiceCustomer() && !Boolean.TRUE.equals(recipients.complaintCustomer()) && safe(recipients.userIds()).isEmpty() && safe(recipients.roles()).isEmpty()
        && safe(recipients.permissions()).isEmpty() && safe(recipients.emails()).isEmpty()) throw NotificationTemplate.bad("Cần ít nhất một nguồn người nhận");
  }
  public List<Recipient> resolve(NotificationRule rule, Invoice invoice) {
    var config = rule.getRecipients(); Map<String, Recipient> recipients = new TreeMap<>();
    for (String id : safe(config.userIds())) add(recipients, directory.user(id, rule.getTenantId()), invoice);
    if (!safe(config.roles()).isEmpty() || !safe(config.permissions()).isEmpty()) for (UserEntity user : directory.users(rule.getTenantId())) {
      var claims = directory.claims(user, rule.getTenantId());
      if (!Collections.disjoint(claims.roles(), safe(config.roles())) || !Collections.disjoint(claims.permissions(), safe(config.permissions()))) add(recipients, user, invoice);
    }
    if (config.invoiceCustomer() && invoice != null && invoice.getInvoiceHolder() != null && invoice.getInvoiceHolder().userId() != null) {
      UserEntity user = directory.user(invoice.getInvoiceHolder().userId(), rule.getTenantId());
      if (user != null && user.getUserCategory() != null && "CUSTOMER".equals(user.getUserCategory().getUserCategoryCode())) add(recipients, user, invoice);
    }
    for (String email : safe(config.emails())) if (settings.approved(rule.getTenantId()).contains(NotificationSettings.canonical(email))) {
      var user = directory.userByEmail(email, rule.getTenantId());
      if (customer(user)) add(recipients, user, invoice);
      else recipients.putIfAbsent(NotificationSettings.canonical(email), new Recipient(NotificationSettings.canonical(email), null));
    }
    if (recipients.size() > 200) throw new IllegalStateException("RECIPIENT_LIMIT_EXCEEDED");
    return List.copyOf(recipients.values());
  }
  private boolean customer(UserEntity user) { return user != null && user.getUserCategory() != null && "CUSTOMER".equals(user.getUserCategory().getUserCategoryCode()); }
  private boolean owns(UserEntity user, Invoice invoice) {
    return invoice != null && invoice.getInvoiceHolder() != null && user.getIdAsString().equals(invoice.getInvoiceHolder().userId())
        && (invoice.getInvoiceHolder().customerId() == null || user.getIdAsString().equals(invoice.getInvoiceHolder().customerId()));
  }
  private void add(Map<String, Recipient> result, UserEntity user, Invoice invoice) {
    if (user != null && (!customer(user) || owns(user, invoice)) && NotificationSettings.validEmail(user.getEmail())) {
      String email = NotificationSettings.canonical(user.getEmail()); result.put(email, new Recipient(email, user.getIdAsString()));
    }
  }
  public boolean stillAllowed(Notification notification) {
    var audience = audiences.stream().filter(a -> a.supports(notification.getType())).findFirst();
    if (audience.isPresent()) return audience.get().stillAllowed(notification);
    if ("COMPLAINT_CREATED".equals(notification.getType()) || "COMPLAINT_RESOLVED".equals(notification.getType())) return false;
    if (notification.getRecipientUserId() == null) {
      var recipient = directory.userByEmail(notification.getSendTo(), notification.getTenantId());
      return settings.approved(notification.getTenantId()).contains(notification.getSendTo()) && (!customer(recipient) || owns(recipient, directory.sourceInvoice(notification)));
    }
    UserEntity user = directory.user(notification.getRecipientUserId(), notification.getTenantId());
    return user != null && (!customer(user) || owns(user, directory.sourceInvoice(notification)))
        && NotificationSettings.validEmail(user.getEmail()) && NotificationSettings.canonical(user.getEmail()).equals(notification.getSendTo());
  }
}
