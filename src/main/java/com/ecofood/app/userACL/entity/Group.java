package com.ecofood.app.userACL.entity;

import com.ecofood.core.entity.BaseEntity;
import com.ecofood.core.enums.AccessEntityTypeEnum;
import com.ecofood.core.enums.AccessLevelEnum;
import com.ecofood.core.enums.GroupType;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Matches DCMP's real Service_SecurityManagement Group entity (confirmed by
 * reading that class directly) — IMPORTANT divergence from the intuitive
 * "user group" reading: DCMP's real Group is NOT user-scoped directly, it's
 * TENANT-scoped via partyRelationships (reusing the exact same
 * PartyToPartyRelationship class as TenantEntity/UserEntity), discriminated
 * by RelationshipTypeEnum.TENANT. Real group codes are tenant TIERS
 * ("DCMP"/"MASTER_TENANT"/"SUB_TENANT"/"INSURER"), not literal
 * "admins"/"viewers" style groups.
 *
 * groupType discriminates the two things this same collection holds, per
 * DCMP's real GroupType: ROLE_GROUP (the role-hierarchy seed rows above) vs
 * ACCESS_CONTROL_GROUP (a Data Access Rule — accessLevel/accessEntityType
 * are only meaningful on that kind). accessLevel/accessEntityType were
 * flagged as deliberately deferred when this entity was first built; now
 * added, narrowed to 2 access levels and the project's own 3 entity types
 * rather than DCMP's real 4-tier/1-type enums (see AccessLevelEnum/
 * AccessEntityTypeEnum javadocs).
 *
 * status's full real field list wasn't confirmed during research, so its
 * nested GroupStatus is kept minimal (code+description).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "group")
public class Group extends BaseEntity {

  @Field("group_name")
  private String name;

  @Field("group_code")
  private String code;

  @Field("group_type")
  private GroupType groupType;

  @Field("group_description")
  private String groupDescription;

  @Field("status")
  private GroupStatus status;

  /** Only meaningful when groupType is ACCESS_CONTROL_GROUP. */
  @Field("access_level")
  private AccessLevelEnum accessLevel;

  /** Only meaningful when groupType is ACCESS_CONTROL_GROUP. */
  @Field("access_entity_type")
  private AccessEntityTypeEnum accessEntityType;

  @Field("party_to_party_relation")
  @Builder.Default
  private List<PartyToPartyRelationship> partyRelationships = new ArrayList<>();

  /** Embeddable status of a Group — see class javadoc on fidelity scope. */
  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class GroupStatus {

    @Field("status_code")
    private String statusCode;

    @Field("status_description")
    private String statusDescription;
  }
}
