package com.ecofood.app.tenant.repository;

import com.ecofood.app.tenant.entity.TenantEntity;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TenantRepository extends MongoRepository<TenantEntity, ObjectId> {

  /** Resolves a Keycloak token's issuing realm back to the tenant that owns it. */
  Optional<TenantEntity> findByRealm(String realm);

  /** Resolves a frontend-supplied "domain" (hostname, or a dev stand-in) back to its tenant. */
  Optional<TenantEntity> findByDomainUrl(String domainUrl);
}
