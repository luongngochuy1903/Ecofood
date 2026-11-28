package com.ecofood.app.enterprise.repository;

import com.ecofood.app.enterprise.entity.EnterpriseEntity;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EnterpriseRepository extends MongoRepository<EnterpriseEntity, ObjectId> {

  @org.springframework.data.mongodb.repository.Query("{'deleted': {$ne: true}, 'party_to_party_relation': {$elemMatch: {'relationship_type_code': 'BL', 'to_party_identifier': ?0}}}")
  List<EnterpriseEntity> findDashboardChildren(ObjectId parent);

  /** Backs the onboarding form's "related enterprise" picker — e.g. every master-level (category=ENTERPRISE) enterprise, for a REGIONAL_ENTERPRISE to attach itself to. */
  List<EnterpriseEntity> findByEnterpriseCategoryEnterpriseCategoryCode(String enterpriseCategoryCode);
}
