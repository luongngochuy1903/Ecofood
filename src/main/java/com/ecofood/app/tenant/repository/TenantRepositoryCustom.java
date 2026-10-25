package com.ecofood.app.tenant.repository;

import com.ecofood.app.tenant.entity.TenantEntity;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Backs the "Tenant" sub-tab directory — split out from TenantRepository
 * because the combined scoping ($or self/sub-tenant-of)+regex-search+paging
 * query can't be expressed as a Spring Data derived query (the only query
 * style used elsewhere in this repository); see TenantRepositoryImpl.
 */
public interface TenantRepositoryCustom {

  /**
   * unrestricted=true (caller is SYSTEM-level) returns every tenant,
   * ignoring scopeTenantId. unrestricted=false returns scopeTenantId's own
   * tenant plus every tenant with a MASTER_TENANT-coded partyRelationships
   * entry pointing at scopeTenantId. search, when non-blank, additionally
   * requires a case-insensitive regex "contains" match on tenantName.
   */
  Page<TenantEntity> findDirectory(ObjectId scopeTenantId, boolean unrestricted, String search, Pageable pageable);

  /**
   * The same self+sub-tenants scope findDirectory's unrestricted=false case
   * applies, as a flat unpaginated id list — backs cross-module callers
   * (e.g. userACL's user directory, via TenantQueryService.
   * getScopedTenantIds) that need the raw scope set rather than a page of
   * TenantEntity.
   */
  List<ObjectId> findScopedTenantIds(ObjectId scopeTenantId);
}
