package com.ecofood.app.common.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.ecofood.app.common.entity.Sequence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class SequenceGeneratorServiceTest {

  @Mock private MongoOperations mongoOperations;

  private SequenceGeneratorService sequenceGeneratorService;

  @Test
  void returnsTheCounterValueFromTheUpsertedDocument() {
    sequenceGeneratorService = new SequenceGeneratorService(mongoOperations);
    Sequence counter = new Sequence();
    counter.setId("tenant_sequence");
    counter.setSeq(5L);
    when(mongoOperations.findAndModify(
            any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Sequence.class)))
        .thenReturn(counter);

    long result = sequenceGeneratorService.generateSequence("tenant_sequence");

    assertThat(result).isEqualTo(5L);
  }

  @Test
  void fallsBackToOneWhenNoDocumentIsReturned() {
    sequenceGeneratorService = new SequenceGeneratorService(mongoOperations);
    when(mongoOperations.findAndModify(
            any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Sequence.class)))
        .thenReturn(null);

    long result = sequenceGeneratorService.generateSequence("tenant_sequence");

    assertThat(result).isEqualTo(1L);
  }
}
