package com.ecofood.app.common.dto;

import com.ecofood.app.userACL.entity.PartnerTypeEnum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lives in common as part of EnterpriseService's public interface contract,
 * consumed cross-module via CoreEnterpriseClient (e.g. by TenantServiceImpl's
 * onboarding flow, for ENTERPRISE/REGIONAL_ENTERPRISE partnerType tenants).
 *
 * Only the caller-variable pieces are request fields — partyName,
 * enterpriseCategory, and partyStatus are all derived inside
 * EnterpriseServiceImpl.createEnterprise(...), not accepted here:
 * partyName = enterpriseCompanyName, enterpriseCategory.code = partnerTypeCode,
 * partyStatus.statusCode = "ACTIVE" (hardcoded — every enterprise starts active).
 *
 * relatedEnterpriseId is OPTIONAL — set only for a REGIONAL_ENTERPRISE that
 * belongs to an already-existing (master) enterprise. When present,
 * createEnterprise(...) adds a BELONGTO("BL") relation on the new enterprise
 * AND updates the existing enterpriseId's own partyRelationships with the
 * reciprocal OWN("OW") relation back to the new one.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnterpriseCreateRequest {

  @NotBlank
  private String enterpriseCompanyName;

  @NotNull
  private PartnerTypeEnum partnerTypeCode;

  private String partnerTypeDescription;

  @Valid
  private EnterpriseContactRequest contact;

  private String relatedEnterpriseId;
}
