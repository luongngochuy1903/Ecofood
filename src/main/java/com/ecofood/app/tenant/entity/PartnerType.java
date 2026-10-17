package com.ecofood.app.tenant.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Matches DCMP's real Service_Tenant PartnerType field names and types
 * (confirmed by reading that class directly) — an embeddable POJO with two
 * plain String fields, not an enum.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartnerType {

  @Field("partner_type_code")
  private String partnerTypeCode;

  @Field("partner_type_description")
  private String partnerTypeDescription;
}
