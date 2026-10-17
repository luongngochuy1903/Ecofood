package com.ecofood.app.common.dto;

import jakarta.validation.Valid;
import java.util.List;
import lombok.Data;

/**
 * Mirrors enterprise.entity.Contact — minus contactPartyId, which is an
 * internal party reference, never set directly by a create request.
 */
@Data
public class EnterpriseContactRequest {

  private String contactPartyName;
  private String contactPartyLastName;
  private String contactDepartment;
  private String contactDesignation;

  @Valid
  private List<EnterpriseCommunicationRequest> communications;
}
