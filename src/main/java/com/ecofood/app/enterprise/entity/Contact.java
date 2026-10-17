package com.ecofood.app.enterprise.entity;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Matches DCMP's real Service_Insurer Contact field names and types exactly
 * (confirmed by reading that class directly) — the enterprise's primary
 * contact person, with one or more Communication channels underneath.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Contact {

  @Field("contact_party_identifier")
  private ObjectId contactPartyId;

  @Field("contact_party_name")
  private String contactPartyName;

  @Field("contact_party_last_name")
  private String contactPartyLastName;

  @Field("contact_department")
  private String contactDepartment;

  @Field("contact_designation")
  private String contactDesignation;

  @Field("communication")
  @Builder.Default
  private List<Communication> communications = new ArrayList<>();
}
