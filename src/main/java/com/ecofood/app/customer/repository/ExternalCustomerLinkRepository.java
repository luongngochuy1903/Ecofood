package com.ecofood.app.customer.repository;

import com.ecofood.app.customer.entity.ExternalCustomerLink;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ExternalCustomerLinkRepository extends MongoRepository<ExternalCustomerLink, String> {
  Optional<ExternalCustomerLink> findByTenantIdAndExternalIdAndRevokedAtIsNullAndLinkedCustomerIdIsNull(
      String tenantId, String externalId);
  Optional<ExternalCustomerLink> findByTokenHashAndRevokedAtIsNull(String tokenHash);
}
