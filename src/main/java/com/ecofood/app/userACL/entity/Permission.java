package com.ecofood.app.userACL.entity;

import com.ecofood.core.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Matches DCMP's real Service_SecurityManagement Permission entity
 * (confirmed by reading that class directly): name/code/description/
 * permissionStatus field names and types. DCMP's real permission codes
 * follow dot-notation ("resource.action.scope", e.g. "products.read.any").
 *
 * NOT included here: DCMP's real permissionCategory (@DBRef to a separate
 * PermissionCategory doc, a UI-grouping concept) — confirmed real but out
 * of the 5-collection scope this module was asked to cover.
 *
 * permissionStatus's full real field list wasn't confirmed during research,
 * so its nested PermissionStatus is kept minimal (code+description) rather
 * than overclaiming fidelity beyond what was verified.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "permission")
public class Permission extends BaseEntity {

  @Field("permission_name")
  private String name;

  @Field("permission_code")
  private String code;

  @Field("permission_description")
  private String description;

  @Field("permission_status")
  private PermissionStatus permissionStatus;

  /** Embeddable status of a Permission — see class javadoc on fidelity scope. */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class PermissionStatus {

    @Field("status_code")
    private String statusCode;

    @Field("status_description")
    private String statusDescription;
  }
}
