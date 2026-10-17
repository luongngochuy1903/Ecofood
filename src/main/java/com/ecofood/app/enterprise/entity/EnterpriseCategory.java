package com.ecofood.app.enterprise.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Matches DCMP's real Service_Insurer InsurerCategory field names and types
 * exactly (confirmed by reading that class directly) — an embeddable POJO,
 * not an enum. Renamed from DCMP's "InsurerCategory" to "EnterpriseCategory"
 * to match this project's own domain terminology (see EnterpriseEntity).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnterpriseCategory {

  @Field("enterprise_category_code")
  private String enterpriseCategoryCode;

  @Field("enterprise_category_description")
  private String enterpriseCategoryDescription;
}
