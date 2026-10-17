package com.ecofood.app.common.dto;

import jakarta.validation.Valid;
import java.util.List;
import lombok.Data;

/** One communication channel under an EnterpriseContactRequest — mirrors enterprise.entity.Communication. */
@Data
public class EnterpriseCommunicationRequest {

  private String communicationChannelName;
  private boolean preferred;

  @Valid
  private List<EnterpriseCommunicationDetailRequest> communicationDetails;
}
