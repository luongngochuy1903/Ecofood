package com.ecofood.app.common.service;

import static org.springframework.data.mongodb.core.FindAndModifyOptions.options;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.ecofood.app.common.entity.Sequence;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Matches DCMP's real Service_Core SequenceGeneratorService exactly — an
 * atomic, upserting Mongo counter shared by any entity that needs a
 * gap-free incrementing sequence (e.g. tenant codes).
 */
@Service
@RequiredArgsConstructor
public class SequenceGeneratorService {

  private final MongoOperations mongoOperations;

  public long generateSequence(String sequenceName) {
    Sequence counter =
        mongoOperations.findAndModify(
            query(where("_id").is(sequenceName)),
            new Update().inc("seq", 1),
            options().returnNew(true).upsert(true),
            Sequence.class);
    return !Objects.isNull(counter) ? counter.getSeq() : 1;
  }
}
