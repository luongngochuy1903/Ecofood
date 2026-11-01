package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.core.enums.RelationshipTypeEnum;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepositoryCustom {

  private final MongoTemplate mongoTemplate;

  @Override
  public Page<UserEntity> findDirectory(
      List<ObjectId> scopedTenantIds, boolean unrestricted, String search, Pageable pageable) {
    Query query = new Query();

    if (!unrestricted) {
      query.addCriteria(
          Criteria.where("party_to_party_relation")
              .elemMatch(
                  Criteria.where("relationship_type_code")
                      .is(RelationshipTypeEnum.TENANT.getCode())
                      .and("to_party_identifier")
                      .in(scopedTenantIds)));
    }

    if (search != null && !search.isBlank()) {
      String pattern = Pattern.quote(search.trim());
      query.addCriteria(
          new Criteria()
              .orOperator(
                  Criteria.where("first_name").regex(pattern, "i"),
                  Criteria.where("last_name").regex(pattern, "i"),
                  Criteria.where("email").regex(pattern, "i")));
    }

    long total = mongoTemplate.count(query, UserEntity.class);
    query.with(pageable);
    List<UserEntity> content = mongoTemplate.find(query, UserEntity.class);

    return new PageImpl<>(content, pageable, total);
  }
}
