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
 * Matches DCMP's real Service_SecurityManagement Role entity (confirmed by
 * reading that class directly): name/code/roleStatus/roleType field names,
 * types, and the "role_sequence" atomic-counter name. Role codes
 * (e.g. "ENTERPRISE_ADMIN", "TENANT_ADMIN", "DCMP_ADMIN") are plain data
 * rows here, NOT a Java enum — real DCMP never backs role codes with an
 * enum since they're managed/extended as data, not compiled constants.
 *
 * roleStatus's full real field list wasn't confirmed during research, so
 * its nested RoleStatus is kept minimal (code+description) rather than
 * overclaiming fidelity beyond what was verified.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "role")
public class Role extends BaseEntity {

  /** Name of this entity's atomic Mongo counter — matches DCMP's real Role.SEQUENCE_NAME. */
  public static final String SEQUENCE_NAME = "role_sequence";

  @Field("role_name")
  private String name;

  @Field("role_code")
  private String code;

  @Field("role_status")
  private RoleStatus roleStatus;

  @Field("role_type")
  private RoleType roleType;

  /** Embeddable status of a Role — see class javadoc on fidelity scope. */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class RoleStatus {

    @Field("status_code")
    private String statusCode;

    @Field("status_description")
    private String statusDescription;
  }
}
