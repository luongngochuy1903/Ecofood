package com.ecofood.app.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Matches DCMP's real Service_Core Sequence model exactly — a Mongo-backed
 * atomic counter. id is the sequence's name (e.g. "tenant_sequence"), seq is
 * its current value. See SequenceGeneratorService for the atomic increment.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "sequence")
public class Sequence {

  @Id
  private String id;

  private long seq;
}
