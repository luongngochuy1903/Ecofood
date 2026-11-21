package com.ecofood.app.proposition.repository;

import com.ecofood.app.proposition.entity.PropositionCurrencySettings;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PropositionCurrencySettingsRepository
        extends MongoRepository<PropositionCurrencySettings, ObjectId> {
    Optional<PropositionCurrencySettings> findByEnterpriseId(ObjectId enterprise);
}
