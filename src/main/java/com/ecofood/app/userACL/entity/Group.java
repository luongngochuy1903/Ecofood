package com.ecofood.app.userACL.entity;

import com.ecofood.app.common.entity.BaseEntity;
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
 * NOT included here: DCMP's real accessLevel/accessEntityType
 * (AccessControlLevelEnum/AccessControlGroupEntityTypeEnum) — confirmed to
 * exist but not read in full, and out of the 5-collection scope this module
 * was asked to cover.
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
  private String groupType;

  @Field("group_description")
  private String groupDescription;

  @Field("status")
  private GroupStatus status;

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
