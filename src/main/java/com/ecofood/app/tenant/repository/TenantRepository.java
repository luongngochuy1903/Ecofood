package com.ecofood.app.tenant.repository;

import com.ecofood.app.tenant.entity.TenantEntity;
import com.ecofood.core.enums.TenantTypeEnum;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TenantRepository extends MongoRepository<TenantEntity, ObjectId>, TenantRepositoryCustom {

  /**
   * Resolves a Keycloak token's issuing realm back to every tenant hosted in
   * it — NOT a single tenant: a realm is shared by a MASTER_TENANT and all
   * of its SUB_TENANTs (see TenantServiceImpl.findMasterTenant), so the
   * caller must further narrow this down to the one the specific logging-in
   * user actually belongs to (see InternalTokenService.
   * mintTokenPairForKeycloakLogin).
   */
  List<TenantEntity> findAllByRealm(String realm);

  /** Resolves a frontend-supplied "domain" (hostname, or a dev stand-in) back to its tenant. */
  Optional<TenantEntity> findByDomainUrl(String domainUrl);

  /** Backs the onboarding form's master-tenant picker — every tenant with tenantType=MASTER_TENANT. */
  List<TenantEntity> findByTenantType(TenantTypeEnum tenantType);

  /** Backs the ECO-onboarding uniqueness guard — exactly one ECO tenant may ever exist platform-wide. */
  boolean existsByPartnerTypePartnerTypeCode(String partnerTypeCode);

  /** Backs the root-tenant lookup — the one ECO tenant, whose own domainUrl is the base every other tenant's domain is composed against. */
  Optional<TenantEntity> findByPartnerTypePartnerTypeCode(String partnerTypeCode);
}
