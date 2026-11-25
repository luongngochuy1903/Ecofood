package com.ecofood.app.proposition.repository;

import com.ecofood.app.proposition.entity.PropositionEntity;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PropositionRepository extends MongoRepository<PropositionEntity, ObjectId> {
    List<PropositionEntity> findByEnterpriseIdAndDeletedFalse(ObjectId enterprise);

    List<PropositionEntity> findByDeletedFalse();

    /** Every Proposition sourced from this Product — a Proposition carries no product version of its own, only which Product it was created from. */
    List<PropositionEntity> findByProductIdAndDeletedFalse(ObjectId productId);

    List<PropositionEntity>
            findByDefinitionDistributionPartnerIdsInOrDefinitionBrandIdInOrPlansTenantIdsIn(
                    List<String> partners, List<String> brands, List<String> tenants);

    List<PropositionEntity> findByDefinitionDistributionPartnerIdsInAndDeletedFalse(
            List<String> partners);
}
