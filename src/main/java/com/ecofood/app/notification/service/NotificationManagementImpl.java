package com.ecofood.app.notification.service;

import com.ecofood.app.notification.config.NotificationSettings;
import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.entity.NotificationRule;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.notification.security.NotificationAccess;
import com.ecofood.core.dto.NotificationDtos;
import com.ecofood.core.service.NotificationManagementService;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class NotificationManagementImpl implements NotificationManagementService {
  private static final String TENANT_ID = "tenantId";
  private static final String STATUS = "status";
  private final MongoTemplate mongo;
  private final NotificationAccess access;
  private final NotificationRecipients recipients;
  private final NotificationDirectory directory;
  private final NotificationSettings settings;
  private final NotificationTemplate templates;
  @Override
  public Map<String, Object> capabilities() {
    var scope = access.current();
    return Map.of("manage", scope.manage(), "read", scope.read(), "retry", scope.retry());
  }
  @Override
  public Map<String, Object> options() {
    var scope = access.current(); scope.requireManage();
    var users = directory.users(scope.tenant());
    Set<String> roles = new TreeSet<>();
    Set<String> permissions = new TreeSet<>();
    var people = users.stream().limit(500).map(user -> {
      var claims = directory.claims(user, scope.tenant()); roles.addAll(claims.roles()); permissions.addAll(claims.permissions());
      return Map.of("id", user.getIdAsString(), "email", Optional.ofNullable(user.getEmail()).orElse(""));
    }).toList();
    return Map.of("events", List.of("INVOICE_PAID", "COMPLAINT_CREATED", "COMPLAINT_RESOLVED"), "placeholders", NotificationTemplate.FIELDS,
        "users", people, "roles", roles, "permissions", permissions, "approvedEmails", settings.approved(scope.tenant()));
  }
  @Override
  public List<NotificationRule> rules() {
    var scope = access.current(); scope.requireManage();
    return mongo.find(Query.query(Criteria.where(TENANT_ID).is(scope.tenant())).with(Sort.by(Sort.Direction.DESC, "updatedAt")).limit(100), NotificationRule.class);
  }
  @Override
  public NotificationRule rule(String id) { var s = access.current(); s.requireManage(); return findRule(id, s.tenant()); }
  private NotificationRule findRule(String id, String tenant) {
    var rule = mongo.findOne(Query.query(Criteria.where("_id").is(id).and(TENANT_ID).is(tenant)), NotificationRule.class);
    if (rule == null) throw missing(); return rule;
  }
  @Override
  public NotificationRule save(String id, NotificationDtos.RuleRequest input) {
    var scope = access.current(); scope.requireManage();
    if (input == null || input.name() == null || input.name().isBlank() || input.name().length() > 150 || !Set.of("INVOICE_PAID", "COMPLAINT_CREATED", "COMPLAINT_RESOLVED").contains(input.eventType() == null ? "" : input.eventType())) throw NotificationTemplate.bad("Tên hoặc sự kiện không hợp lệ");
    templates.validateForEvent(input.eventType(), input.subject(), input.content()); recipients.validate(input.recipients(), scope.tenant());
    recipients.validateEvent(input.eventType(), input.recipients(), scope.tenant());
    if (input.enabled() && !NotificationSettings.validEmail(settings.from)) throw NotificationTemplate.bad("Cần cấu hình NOTIFICATION_FROM hợp lệ trước khi bật rule");
    Instant now = Instant.now();
    if (id == null) {
      var rule = new NotificationRule(); rule.setId(new ObjectId().toHexString()); rule.setTenantId(scope.tenant());
      rule.setName(input.name().trim()); rule.setEventType(input.eventType()); rule.setEnabled(input.enabled()); rule.setRecipients(input.recipients());
      rule.setSubject(input.subject()); rule.setContent(input.content()); rule.setRevision(1); rule.setCreatedAt(now); rule.setUpdatedAt(now);
      rule.setCreatedBy(scope.actor()); rule.setUpdatedBy(scope.actor()); rule.setAudit(List.of(new NotificationDtos.Change(1, now, scope.actor(), "CREATED")));
      return mongo.insert(rule);
    }
    findRule(id, scope.tenant());
    if (input.revision() == null || input.revision() < 1) throw NotificationTemplate.bad("Cần revision hiện tại khi cập nhật");
    Update update = new Update().set("name", input.name().trim()).set("eventType", input.eventType()).set("enabled", input.enabled())
        .set("recipients", input.recipients()).set("subject", input.subject()).set("content", input.content());
    return updateRule(id, input.revision(), scope, update, "UPDATED");
  }
  @Override
  public NotificationRule enable(String id, NotificationDtos.EnabledRequest request) {
    var scope = access.current(); scope.requireManage(); var rule = findRule(id, scope.tenant());
    if (rule.getEventType() != null && rule.getEventType().startsWith("COMPLAINT_")) recipients.validateEvent(rule.getEventType(), rule.getRecipients(), scope.tenant());
    if (request == null) throw NotificationTemplate.bad("Thiếu cấu hình trạng thái");
    if (request.enabled() && !NotificationSettings.validEmail(settings.from)) throw NotificationTemplate.bad("Cần cấu hình NOTIFICATION_FROM hợp lệ trước khi bật rule");
    return updateRule(id, request.revision(), scope, new Update().set("enabled", request.enabled()), request.enabled() ? "ENABLED" : "DISABLED");
  }
  private NotificationRule updateRule(String id, long revision, NotificationAccess.Scope scope, Update update, String action) {
    Instant now = Instant.now();
    var result = mongo.findAndModify(Query.query(Criteria.where("_id").is(id).and(TENANT_ID).is(scope.tenant()).and("revision").is(revision)),
        update.inc("revision", 1).set("updatedAt", now).set("updatedBy", scope.actor())
          .push("audit", new NotificationDtos.Change(revision + 1, now, scope.actor(), action)), FindAndModifyOptions.options().returnNew(true), NotificationRule.class);
    if (result == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Rule đã thay đổi; tải lại trước khi lưu"); return result;
  }
  @Override
  public NotificationDtos.Page<Notification> history(String event, String status, Instant from, Instant to, int page, int size) {
    var scope = access.current(); scope.requireRead();
    if (page < 0 || page > 100000 || size < 1 || size > 100) throw NotificationTemplate.bad("Phân trang không hợp lệ");
    Query query = Query.query(Criteria.where(TENANT_ID).is(scope.tenant()));
    if (event != null && !event.isBlank()) query.addCriteria(Criteria.where("type").is(event));
    if (status != null && !status.isBlank()) query.addCriteria(Criteria.where(STATUS).is(status));
    if (from != null || to != null) { var period = Criteria.where("createdAt");
      if (from != null) {
        period.gte(from);
      }
      if (to != null) {
        period.lte(to);
      }
      query.addCriteria(period); }
    var rows = mongo.find(query.with(Sort.by(Sort.Direction.DESC, "createdAt", "id")).skip((long) page * size).limit(size + 1), Notification.class);
    return new NotificationDtos.Page<>(List.copyOf(rows.subList(0, Math.min(size, rows.size()))), page, size, rows.size() > size);
  }
  @Override
  public Notification detail(String id) {
    var scope = access.current(); scope.requireRead();
    var result = mongo.findOne(Query.query(Criteria.where("_id").is(id).and(TENANT_ID).is(scope.tenant())), Notification.class);
    if (result == null) throw missing(); return result;
  }
  @Override
  public Notification retry(String id, NotificationDtos.RetryRequest input) {
    var scope = access.current(); scope.requireRetry(); var current = detail(id);
    if (!"FAILED".equals(current.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ retry Notification FAILED");
    if (current.isDeliveryUncertain() && (input == null || !input.acknowledgeDuplicateRisk())) throw NotificationTemplate.bad("Cần xác nhận nguy cơ gửi trùng vì kết quả SMTP trước đó chưa xác định");
    var result = mongo.findAndModify(Query.query(Criteria.where("_id").is(id).and(TENANT_ID).is(scope.tenant()).and(STATUS).is("FAILED").and("totalAttempts").is(current.getTotalAttempts())),
        new Update().set(STATUS, "PENDING").set("retryCount", 0).set("nextAttemptAt", Instant.now()).set("deliveryUncertain", false)
          .set("manualRetryBy", scope.actor()).set("manualRetryAt", Instant.now()), FindAndModifyOptions.options().returnNew(true), Notification.class);
    if (result == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Notification đã thay đổi"); return result;
  }
  private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy dữ liệu trong Tenant hiện tại"); }
}
