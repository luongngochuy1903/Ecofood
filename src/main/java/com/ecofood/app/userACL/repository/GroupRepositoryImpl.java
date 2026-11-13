package com.ecofood.app.userACL.repository;

import com.ecofood.app.userACL.entity.Group;
import com.ecofood.core.enums.GroupType;
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
public class GroupRepositoryImpl implements GroupRepositoryCustom {

  private final MongoTemplate mongoTemplate;

  @Override
  public Page<Group> findDirectory(
      GroupType groupType, List<ObjectId> scopedTenantIds, boolean unrestricted, String search, Pageable pageable) {
    Query query = new Query(Criteria.where("group_type").is(groupType));

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
      query.addCriteria(Criteria.where("group_name").regex(Pattern.quote(search.trim()), "i"));
    }

    long total = mongoTemplate.count(query, Group.class);
    query.with(pageable);
    List<Group> content = mongoTemplate.find(query, Group.class);

    return new PageImpl<>(content, pageable, total);
  }
}
