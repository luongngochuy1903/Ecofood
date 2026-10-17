package com.ecofood.app.userACL.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Matches DCMP's real Service_Core PartyToPartyRelationship (field names,
 * types, and the typo'd "relationship_type_decription" field, confirmed by
 * reading that class directly) — a generic party-relation record
 * (tenant/customer/contact/...), discriminated by relationshipTypeCode.
 *
 * NOT storing roles here: role codes used to live on this relation as a
 * disclosed simplification, but that's been removed — roles will live in a
 * separate collection TBD, not yet designed. DCMP's own real JwtTokenUtil
 * sources roles from a separate ACL microservice
 * (Service_SecurityManagement's TenantRoleResultDTO), not from this record.
 *
 * NOT added here: a per-relation externalLoginId. Confirmed by reading
 * DCMP's real switch-tenant flow (Service_Auth's SwitchTenantAuthServiceImpl)
 * directly: a user has exactly ONE Keycloak identity (UserEntity's single
 * top-level externalLoginId) regardless of how many tenants they have role
 * assignments in — "switching tenant" is purely a DCMP-JWT-layer re-mint
 * (different tenantId claim from this same relationships list), never a new
 * Keycloak login. This project's UserService mirrors into Keycloak once,
 * for the first tenant assignment only; other assignments are pure
 * authorization records with no Keycloak identity of their own.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyToPartyRelationship {

  @Field("to_party_identifier")
  private ObjectId toPartyId;

  @Field("relationship_type_code")
  private String relationshipTypeCode;

  @Field("relationship_type_decription")
  private String relationshipTypeDecription;

  @Field("relationship_reference_code")
  private String relationshipReferenceCode;

  @Field("relationship_reference_name")
  private String relationshipReferenceName;
}
