package com.ecofood.app.common.entity;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Matches DCMP's real Service_Core PartyStatus field names and types
 * (confirmed by reading that class directly) — an embeddable POJO, not an
 * enum. Placed here in common (not in the tenant package) because DCMP's
 * real PartyStatus lives at the shared Party base-entity level, used by any
 * Party subtype (Tenant today; User could reuse it later).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PartyStatus {

  @Field("status_code")
  private String statusCode;

  @Field("status_description")
  private String statusDescription;

  @Field("status_remarks")
  private String statusRemarks;

  @Field("status_effective_date")
  private Instant statusEffectiveDate;

  @Field("reason_code")
  private String reasonCode;

  @Field("reason_description")
  private String reasonDescription;
}
