package com.ecofood.app.notification.integration;

import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.core.service.UserAccessService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationDirectory {
  private static final String TENANT_ID = "tenantId";
  private final MongoTemplate mongo;
  private final UserAccessService access;
  public static boolean belongs(UserEntity user, String tenant) {
    return user != null && !Boolean.TRUE.equals(user.getDeleted()) && user.getPartyRelationships() != null
        && user.getPartyRelationships().stream().anyMatch(r -> "TENANT".equals(r.getRelationshipTypeCode())
        && r.getToPartyId() != null && r.getToPartyId().toHexString().equals(tenant));
  }
  public UserEntity user(String id, String tenant) {
    if (!ObjectId.isValid(id)) return null;
    UserEntity user = mongo.findById(new ObjectId(id), UserEntity.class);
    return belongs(user, tenant) ? user : null;
  }
  public List<UserEntity> users(String tenant) {
    return mongo.find(Query.query(Criteria.where("deleted").ne(true).and("partyRelationships").elemMatch(
        Criteria.where("relationshipTypeCode").is("TENANT").and("toPartyId").is(new ObjectId(tenant)))), UserEntity.class);
  }
  public UserAccessService.UserAccessClaims claims(UserEntity user, String tenant) {
    return access.resolveAccess(user.getId(), new ObjectId(tenant));
  }
  public UserEntity userByEmail(String email, String tenant) {
    var query = Query.query(Criteria.where("deleted").ne(true).and("email").regex("^" + java.util.regex.Pattern.quote(email) + "$", "i")
        .and("partyRelationships").elemMatch(Criteria.where("relationshipTypeCode").is("TENANT").and("toPartyId").is(new ObjectId(tenant))));
    return mongo.findOne(query, UserEntity.class);
  }
  public com.ecofood.app.transaction.entity.Invoice sourceInvoice(Notification notice) {
    var event = mongo.findOne(Query.query(Criteria.where("_id").is(notice.getSubmissionId()).and(TENANT_ID).is(notice.getTenantId())), com.ecofood.app.submission.entity.Submission.class);
    if (event == null || !"INVOICE".equals(event.sourceType())) return null;
    return mongo.findOne(Query.query(Criteria.where("_id").is(event.sourceId()).and(TENANT_ID).is(notice.getTenantId())), com.ecofood.app.transaction.entity.Invoice.class);
  }
  public com.ecofood.app.submission.entity.InvoiceBatchSubmission sourceBatch(Notification notice) {
    return mongo.findOne(Query.query(Criteria.where("_id").is(notice.getSubmissionId())
        .and(TENANT_ID).is(notice.getTenantId()).and("sourceType").is("INVOICE_BATCH")
        .and("event").is("INVOICE_PAID")),com.ecofood.app.submission.entity.InvoiceBatchSubmission.class);
  }
}
