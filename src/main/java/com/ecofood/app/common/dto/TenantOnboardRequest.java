package com.ecofood.app.common.dto;

import com.ecofood.app.userACL.entity.PartnerTypeEnum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

/**
 * POST /api/v1/tenants — onboards a brand-new tenant (its own Keycloak
 * realm + public client) AND its first user in one call, delegating to the
 * same UserServiceImpl.createUser(...) path POST /api/v1/users uses.
 *
 * contact/relatedEnterpriseId only matter for ENTERPRISE/REGIONAL_ENTERPRISE
 * partnerTypeCode — ignored otherwise. relatedEnterpriseId lets a
 * REGIONAL_ENTERPRISE declare which already-existing (master) enterprise it
 * belongs to; see TenantServiceImpl.onboardTenant/EnterpriseServiceImpl for
 * the resulting BELONGTO("BL")/OWN("OW") relation pair.
 *
 * Lives in common as part of TenantService's public interface contract.
 */
@Data
public class TenantOnboardRequest {

  @NotBlank
  private String tenantName;

  @NotBlank
  private String username;

  @NotBlank
  @Email
  private String email;

  private String firstName;

  private String lastName;

  private String domainUrl;

  @NotNull
  private PartnerTypeEnum partnerTypeCode;

  private String partnerTypeDescription;

  @Valid
  private List<TenantPartyRelationshipRequest> partyRelationships;

  @Valid
  private EnterpriseContactRequest contact;

  private String relatedEnterpriseId;
}
