package com.ecofood.app.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/** Lives in common as EnterpriseService's public response contract, consumed via CoreEnterpriseClient. */
@Data
@AllArgsConstructor
@Builder
public class EnterpriseResponse {
  private String enterpriseId;
  private String enterpriseCompanyName;
}
