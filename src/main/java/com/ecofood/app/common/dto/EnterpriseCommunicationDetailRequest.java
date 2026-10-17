package com.ecofood.app.common.dto;

import lombok.Data;

/** One physical contact point under an EnterpriseCommunicationRequest channel — mirrors enterprise.entity.CommunicationDetail. */
@Data
public class EnterpriseCommunicationDetailRequest {

  private String locationType;
  private boolean preferred;
  private String addressLine1;
  private String addressLine2;
  private String isoCityCode;
  private String isoCountryCode;
  private String postalCode;
  private String internationalTelecomCountryCode;
  private String phoneNumber;
  private String emailAddress;
}
