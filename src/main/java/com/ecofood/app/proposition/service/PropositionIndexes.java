package com.ecofood.app.proposition.service;

import com.ecofood.app.proposition.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PropositionIndexes implements InitializingBean {
    private final MongoTemplate mongo;

    @Override
    public void afterPropertiesSet() {
        var index = mongo.indexOps(PropositionEntity.class);
        index.ensureIndex(new Index().on("enterpriseId", Sort.Direction.ASC));
        index.ensureIndex(new Index().on("productProcessId", Sort.Direction.ASC));
        index.ensureIndex(new Index().on("definition.distributionPartnerIds", Sort.Direction.ASC));
        index.ensureIndex(new Index().on("definition.brandId", Sort.Direction.ASC));
        index.ensureIndex(new Index().on("plans.tenantIds", Sort.Direction.ASC));
        mongo.indexOps(PropositionCurrencySettings.class)
                .ensureIndex(new Index().on("enterpriseId", Sort.Direction.ASC).unique());
    }
}
