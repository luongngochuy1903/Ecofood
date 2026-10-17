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
 * Matches DCMP's real Service_SecurityManagement UserRole entity (confirmed
 * by reading that class directly) — the User-to-Role junction, and
 * confirmed to be TENANT-SCOPED: one UserRole document per (user, tenant)
 * pair, so the same user can hold different roles in different tenants.
 * roles is a list of EMBEDDED role snapshots (id/code/name/roleType),
 * unlike GroupRole's plain id-reference list — a role-code/name rename
 * later doesn't retroactively change what a past-assigned user actually had.
 *
 * insurerId/location are confirmed real (insurer-scoped roles; a 2-letter
 * ISO country code used as a customer-only sharding key, null for partner
 * users) but are not populated by anything in this project yet.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "user_role")
public class UserRole extends BaseEntity {

  @Field("user_identifier")
  @Indexed
  private ObjectId userId;

  @Field("tenant_identifier")
  @Indexed
  private ObjectId tenantId;

  @Field("roles")
  @Builder.Default
  private List<EmbeddedRole> roles = new ArrayList<>();

  @Field("location")
  private String location;

  /** Embedded snapshot of a Role at assignment time — matches DCMP's real EmbeddedRole. */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class EmbeddedRole {

    @Field("id")
    private ObjectId id;

    private String code;

    private String name;

    @Field("role_type")
    private RoleType roleType;
  }
}
