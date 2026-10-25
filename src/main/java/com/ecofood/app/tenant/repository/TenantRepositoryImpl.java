package com.ecofood.app.tenant.repository;

import com.ecofood.app.tenant.entity.TenantEntity;
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
public class TenantRepositoryImpl implements TenantRepositoryCustom {

  private final MongoTemplate mongoTemplate;

  @Override
  public Page<TenantEntity> findDirectory(
      ObjectId scopeTenantId, boolean unrestricted, String search, Pageable pageable) {
    Query query = new Query();

    if (!unrestricted) {
      query.addCriteria(scopeCriteria(scopeTenantId));
    }

    if (search != null && !search.isBlank()) {
      query.addCriteria(
          Criteria.where("tenant_name").regex(Pattern.quote(search.trim()), "i"));
    }

    long total = mongoTemplate.count(query, TenantEntity.class);
    query.with(pageable);
    List<TenantEntity> content = mongoTemplate.find(query, TenantEntity.class);

    return new PageImpl<>(content, pageable, total);
  }

  @Override
  public List<ObjectId> findScopedTenantIds(ObjectId scopeTenantId) {
    Query query = new Query(scopeCriteria(scopeTenantId));
    query.fields().include("_id");
    return mongoTemplate.find(query, TenantEntity.class).stream().map(TenantEntity::getId).toList();
  }

  /** Self, or any tenant with a MASTER_TENANT-coded relation pointing back at scopeTenantId. */
  private Criteria scopeCriteria(ObjectId scopeTenantId) {
    return new Criteria()
        .orOperator(
            Criteria.where("_id").is(scopeTenantId),
            Criteria.where("party_to_party_relation")
                .elemMatch(
                    Criteria.where("relationship_type_code")
                        .is(RelationshipTypeEnum.MASTER_TENANT.getCode())
                        .and("to_party_identifier")
                        .is(scopeTenantId)));
  }
}
