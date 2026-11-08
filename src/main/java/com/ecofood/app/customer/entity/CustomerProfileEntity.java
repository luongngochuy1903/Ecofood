package com.ecofood.app.customer.entity;

import com.ecofood.core.entity.BaseEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/** Tenant-specific customer preferences; login/email belong to app_user/Keycloak. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "customer_profile")
@CompoundIndex(name = "ux_customer_profile_tenant_user",
    def = "{'tenant_identifier': 1, 'user_identifier': 1}", unique = true)
public class CustomerProfileEntity extends BaseEntity {
  @Field("tenant_identifier")
  private ObjectId tenantId;

  @Field("user_identifier")
  private ObjectId userId;

  @Field("first_name")
  private String firstName;

  @Field("last_name")
  private String lastName;

  @Field("phone_number")
  private String phoneNumber;

  @Field("address")
  private String address;

  /** Embedded audit entries are atomically saved with this profile document. */
  @Field("change_history")
  @Builder.Default
  private List<ProfileChange> changeHistory = new ArrayList<>();

  @Getter
  @Setter
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ProfileChange {
    private Instant changedAt;
    private String actorUserId;
    private List<String> changedFields;
  }
}
