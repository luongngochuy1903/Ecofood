package com.ecofood.app.enterprise.entity;

import com.ecofood.app.common.entity.BaseEntity;
import com.ecofood.app.common.entity.PartyStatus;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
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
 * Maps DCMP's real Service_Insurer Insurer entity field-for-field (confirmed
 * by reading that class, its Party base class, and InsurerCategory/Contact/
 * Communication/CommunicationDetail directly) — collection, class, and field
 * names renamed from "insurer"/"insurance" to "enterprise" to match this
 * project's own domain terminology (PartnerTypeEnum's ENTERPRISE/
 * REGIONAL_ENTERPRISE/...; see EnterpriseCategory), but every OTHER field
 * name/type below stays exactly as DCMP declared it, including the
 * base-Party fields (partyName/partyLastName/partyDescription/partyStatus/
 * partyRelationships), which DCMP's Insurer inherits rather than declares
 * directly.
 *
 * partyStatus/partyRelationships reuse the SAME PartyStatus/
 * PartyToPartyRelationship classes TenantEntity already uses — DCMP's real
 * Party base entity is the single shared source for both, so this project
 * keeps one definition rather than a per-module copy.
 *
 * lobs (List<InsurerLob>, a separate DCMP collection/relationship) is
 * intentionally NOT included yet — out of scope for this first pass.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "enterprise")
public class EnterpriseEntity extends BaseEntity {

  @Field("party_name")
  private String partyName;

  @Field("party_description")
  private String partyDescription;

  @Field("party_status")
  private PartyStatus partyStatus;

  @Field("party_to_party_relation")
  @Builder.Default
  private List<PartyToPartyRelationship> partyRelationships = new ArrayList<>();

  @Field("enterprise_company_code")
  private String enterpriseCompanyCode;

  @Field("company_logo_url")
  private String logo;

  @Field("enterprise_category")
  private EnterpriseCategory enterpriseCategory;

  @Field("contact")
  private Contact contact;
}
