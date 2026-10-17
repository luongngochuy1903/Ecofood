package com.ecofood.app.userACL.entity;

import com.ecofood.app.common.entity.BaseEntity;
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
 * Matches DCMP's real Service_SecurityManagement collection of this exact
 * name, "role_permission" (confirmed by reading that class directly) — the
 * Role-to-Permission junction. NOT one of the 5 collections explicitly
 * named ("role, permissions, group_role, user_role, group"), but added here
 * because DCMP's real "permission" collection is only the catalog — without
 * this junction, a Role has no way to actually carry permissions.
 *
 * permissions is a list of EMBEDDED permission snapshots (id/code/name),
 * matching DCMP's real model — same embedding rationale as UserRole.roles.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "role_permission")
public class RolePermission extends BaseEntity {

  @Field("role_identifier")
  @Indexed
  private ObjectId roleId;

  @Field("permissions")
  @Builder.Default
  private List<EmbeddedPermission> permissions = new ArrayList<>();

  private String description;

  /** Embedded snapshot of a Permission at assignment time — matches DCMP's real EmbeddedPermission. */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class EmbeddedPermission {

    @Field("id")
    private ObjectId id;

    private String code;

    private String name;
  }
}
