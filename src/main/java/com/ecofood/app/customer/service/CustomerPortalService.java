package com.ecofood.app.customer.service;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.customer.auth.CustomerIdentityService;
import com.ecofood.app.customer.dto.CustomerProfileResponse;
import com.ecofood.app.customer.dto.CustomerProfileUpdateRequest;
import com.ecofood.app.customer.entity.CustomerProfileEntity;
import com.ecofood.app.customer.repository.CustomerProfileRepository;
import com.ecofood.app.userACL.entity.UserEntity;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;

/** Profile preferences are local; verified email/subject/credentials remain owned by Keycloak. */
@Service
@RequiredArgsConstructor
public class CustomerPortalService {
  private final CustomerIdentityService identities;
  private final CustomerProfileRepository profiles;
  private final MongoTemplate mongo;
  private final Clock clock;

  public CustomerProfileResponse getMyProfile() {
    var user = identities.requireSelfServiceAccount("CUSTOMER_SELF_READ");
    return response(user, find(user).orElse(null));
  }

  public CustomerProfileResponse updateMyProfile(CustomerProfileUpdateRequest request) {
    var user = identities.requireSelfServiceAccount("CUSTOMER_PROFILE_UPDATE");
    if (request == null) throw new BadRequestException("Profile request is required");
    var current = find(user).orElse(null);
    var changed = new LinkedHashMap<String, String>();
    change(changed, "firstName", request.firstName(), firstName(user, current), true);
    change(changed, "lastName", request.lastName(), lastName(user, current), true);
    change(changed, "phoneNumber", request.phoneNumber(), current == null ? "" : current.getPhoneNumber(), false);
    change(changed, "address", request.address(), current == null ? "" : current.getAddress(), false);
    if (changed.isEmpty()) return response(user, current);

    Instant now = Instant.ofEpochMilli(clock.millis());
    var query = new Query(Criteria.where("tenantId").is(tenant(user)).and("userId").is(user.getId()).and("deleted").ne(true));
    // Concurrent retries with the same values must not append another audit/event.
    query.addCriteria(new Criteria().orOperator(changed.entrySet().stream()
        .map(entry -> Criteria.where(entry.getKey()).ne(entry.getValue())).toArray(Criteria[]::new)));
    var update = new Update().set("updatedAt", now).set("updatedBy", user.getIdAsString())
        .setOnInsert("createdAt", now).setOnInsert("createdBy", user.getIdAsString()).setOnInsert("deleted", false);
    changed.forEach(update::set);
    if (!changed.containsKey("firstName")) update.setOnInsert("firstName", firstName(user, current));
    if (!changed.containsKey("lastName")) update.setOnInsert("lastName", lastName(user, current));
    var audit = new Document("changedAt", now).append("actorUserId", user.getIdAsString()).append("changedFields", new ArrayList<>(changed.keySet()));
    update.push("changeHistory", new Document("$each", List.of(audit)));
    update.push("notificationOutbox", com.ecofood.app.notification.NotificationOutboxEvent.account(
        com.ecofood.app.notification.NotificationOutboxEvent.Kind.PROFILE_UPDATED, user.getIdAsString(), tenant(user).toHexString(), now));
    CustomerProfileEntity saved;
    try {
      saved = mongo.findAndModify(query, update, FindAndModifyOptions.options().upsert(true).returnNew(true), CustomerProfileEntity.class);
    } catch (DuplicateKeyException ex) {
      // Two first PATCH requests may race on the unique tenant/user index. Apply only our fields to the winner.
      saved = mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), CustomerProfileEntity.class);
    }
    if (saved == null) {
      var concurrent = find(user).orElse(null);
      if (concurrent != null && changed.entrySet().stream().allMatch(entry -> entry.getValue().equals(switch (entry.getKey()) {
        case "firstName" -> firstName(user, concurrent); case "lastName" -> lastName(user, concurrent);
        case "phoneNumber" -> Objects.toString(concurrent.getPhoneNumber(), ""); default -> Objects.toString(concurrent.getAddress(), "");
      }))) return response(user, concurrent);
      throw new IllegalStateException("Customer profile unavailable; retry the request");
    }
    return response(user, saved);
  }

  private Optional<CustomerProfileEntity> find(UserEntity user) {
    return profiles.findByTenantIdAndUserIdAndDeletedNot(tenant(user), user.getId(), true);
  }
  private ObjectId tenant(UserEntity user) { return user.getPartyRelationships().get(0).getToPartyId(); }
  private String firstName(UserEntity user, CustomerProfileEntity profile) {
    return Objects.toString(profile == null || profile.getFirstName() == null ? user.getFirstName() : profile.getFirstName(), "");
  }
  private String lastName(UserEntity user, CustomerProfileEntity profile) {
    return Objects.toString(profile == null || profile.getLastName() == null ? user.getLastName() : profile.getLastName(), "");
  }
  private void change(Map<String, String> changed, String key, String requested, String previous, boolean name) {
    if (requested == null) return;
    String value = requested.strip();
    if (name && value.isBlank()) throw new BadRequestException("Name cannot be blank");
    if (!value.equals(Objects.toString(previous, ""))) changed.put(key, value);
  }
  private CustomerProfileResponse response(UserEntity user, CustomerProfileEntity profile) {
    String first = firstName(user, profile), last = lastName(user, profile);
    return new CustomerProfileResponse(user.getIdAsString(), tenant(user).toHexString(), user.getEmail(), first, last,
        profile == null ? "" : Objects.toString(profile.getPhoneNumber(), ""),
        profile == null ? "" : Objects.toString(profile.getAddress(), ""),
        profile == null ? user.getUpdatedAt() : profile.getUpdatedAt(), user.getIdAsString(),
        (first + " " + last).strip(), user.getCreatedAt(), user.getCustomerIdentityIssuer() + "/account/");
  }
}
