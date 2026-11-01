package com.ecofood.app.userACL.entity;

import com.ecofood.core.entity.BaseEntity;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Matches DCMP's real Service_SecurityManagement UserGroupEntity exactly
 * (confirmed by reading that class directly) — ONE document PER USER (not
 * per Group), embedding a snapshot list of which Access Control Groups
 * (Data Access Rules) that user has. Membership lives on the user's side,
 * not the group's — same denormalized-embed pattern UserRole already uses
 * for roles (one doc per (user, tenant), not a members[] array on the role).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "user_group_entity")
public class UserGroupEntity extends BaseEntity {

  @Field("user_identifier")
  @Indexed
  private ObjectId userIdentifier;

  @Field("groups")
  @Builder.Default
  private List<EmbeddedRestrictionGroup> groups = new ArrayList<>();

  /** Embedded snapshot of a Group at assignment time — matches DCMP's real EmbeddedRestrictionGroup. */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class EmbeddedRestrictionGroup {

    @Field("group_identifier")
    private ObjectId groupIdentifier;

    @Field("group_code")
    private String groupCode;

    @Field("group_name")
    private String groupName;

    @Field("tenant_identifier")
    private ObjectId tenantIdentifier;
  }
}
