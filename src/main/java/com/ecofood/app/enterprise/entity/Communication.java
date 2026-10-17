package com.ecofood.app.enterprise.entity;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Matches DCMP's real Service_Insurer Communication field names and types
 * exactly (confirmed by reading that class directly) — one communication
 * channel (e.g. "office", "billing"), holding one or more CommunicationDetail
 * entries.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Communication {

  @Field("communication_channel_name")
  private String communicationChannelName;

  @Field("is_preferred")
  private boolean preferred;

  @Field("communication_details")
  @Builder.Default
  private List<CommunicationDetail> communicationDetails = new ArrayList<>();
}
