package com.ecofood.app.tenant.entity;

import com.ecofood.core.entity.BaseEntity;
import com.ecofood.core.entity.PartyStatus;
import com.ecofood.core.enums.TenantTypeEnum;
import com.ecofood.app.enterprise.entity.Contact;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
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
 * id (inherited from BaseEntity, an ObjectId) doubles as both the Mongo
 * document id and the Keycloak realm's id — set explicitly in TenantService
 * when building the RealmRepresentation, via getIdAsString() — one tenant
 * identity shared by both sides, no separate mapping table.
 *
 * createdAt/updatedAt/createdBy/updatedBy/deleted are also inherited from
 * BaseEntity and auto-filled by Spring Data Mongo auditing — never set
 * manually (see BaseEntity/MongoAuditingConfig).
 *
 * partyRelationships reuses the SAME PartyToPartyRelationship class as
 * UserEntity, discriminated by RelationshipTypeEnum's MASTER_TENANT("MT")/
 * SUB_TENANT("ST")/BELONGTO("BL")/OWN("OW") codes for tenant-to-tenant
 * links — mirroring DCMP's real model, where Party (the common base of
 * both Tenant and User) carries one generic partyRelationships list rather
 * than a tenant-specific relationship class (confirmed by reading DCMP's
 * real Tenant/Party source directly).
 *
 * domainUrl/tenantCode/partnerType are confirmed against DCMP's real
 * Service_Tenant Tenant entity (domain_url, tenant_code, partner_type).
 * status (PartyStatus) is confirmed against DCMP's real Service_Core
 * PartyStatus, inherited there via Party rather than declared directly on
 * Tenant — modeled here as a direct field for simplicity since this
 * project has no separate Party superclass.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "tenant")
public class TenantEntity extends BaseEntity {

  /** Name of this entity's atomic Mongo counter — matches DCMP's real Tenant.SEQUENCE_NAME. */
  public static final String SEQUENCE_NAME = "tenant_sequence";

  @Field("tenant_name")
  private String tenantName;

  @Indexed
  private String realm;

  @Field("client_id")
  private String clientId;

  @Field("domain_url")
  private String domainUrl;

  @Field("tenant_code")
  private String tenantCode;

  @Field("partner_type")
  private PartnerType partnerType;

  /**
   * This tenant's standing in the tenant hierarchy — STANDALONE by default;
   * SUB_TENANT is expected to also carry a partyRelationships entry whose
   * relationshipTypeCode is MASTER_TENANT pointing at the master it belongs
   * to (see TenantServiceImpl.onboardTenant). Drives the onboarding form's
   * "pick a master tenant" dropdown, which lists every tenant with
   * tenantType=MASTER_TENANT.
   */
  @Field("tenant_type")
  @Builder.Default
  private TenantTypeEnum tenantType = TenantTypeEnum.STANDALONE;

  @Field("party_status")
  private PartyStatus status;

  /**
   * Every tenant's primary point of contact, required regardless of
   * partnerType (see TenantOnboardRequest.contact). For ENTERPRISE/
   * REGIONAL_ENTERPRISE tenants, the same contact is ALSO copied onto the
   * companion EnterpriseEntity (see TenantServiceImpl.onboardTenant) — the
   * two are independent copies, not a shared reference.
   */
  @Field("contact")
  private Contact contact;

  @Field("party_to_party_relation")
  @Builder.Default
  private List<PartyToPartyRelationship> partyRelationships = new ArrayList<>();

  /**
   * Set only for ENTERPRISE/REGIONAL_ENTERPRISE partnerType tenants — points
   * at the EnterpriseEntity created alongside this tenant during onboarding
   * (see TenantServiceImpl.onboardTenant). Null for every other partnerType.
   */
  @Field("enterprise_identifier")
  private ObjectId enterpriseId;

  /** Hex-string convenience accessor over enterpriseId — kept explicit since Lombok can't generate this conversion. */
  public String getEnterpriseIdAsString() {
    return enterpriseId != null ? enterpriseId.toHexString() : null;
  }

  /** Hex-string convenience accessor over enterpriseId — kept explicit since Lombok can't generate this conversion. */
  public void setEnterpriseId(String enterpriseId) {
    this.enterpriseId = enterpriseId != null ? new ObjectId(enterpriseId) : null;
  }
}
