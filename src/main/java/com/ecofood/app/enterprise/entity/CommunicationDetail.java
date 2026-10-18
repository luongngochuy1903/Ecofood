package com.ecofood.app.enterprise.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Matches DCMP's real Service_Insurer CommunicationDetail field names and
 * types exactly (confirmed by reading that class directly) — one physical
 * contact point (address/phone/email) under a Communication channel.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommunicationDetail {

  @Field("location_type")
  private String locationType;

  @Field("is_preferred")
  private boolean preferred;

  @Field("address_line_1")
  private String addressLine1;

  @Field("address_line_2")
  private String addressLine2;

  @Field("iso_city_code")
  private String isoCityCode;

  @Field("iso_country_code")
  private String isoCountryCode;

  @Field("postal_code")
  private String postalCode;

  @Field("international_telecom_country_code")
  private String internationalTelecomCountryCode;

  @Field("phone_number")
  private String phoneNumber;

  @Field("email_address")
  private String emailAddress;
}
