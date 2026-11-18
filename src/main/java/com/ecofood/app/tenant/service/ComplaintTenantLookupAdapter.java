package com.ecofood.app.tenant.service;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintDirectorySources;
import com.ecofood.app.tenant.repository.TenantRepository;
import com.ecofood.core.enums.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class ComplaintTenantLookupAdapter implements ComplaintDirectorySources.Tenants {
  private final TenantRepository tenants;
  public Optional<TenantInfo> find(String id) {
    if (id == null || !ObjectId.isValid(id)) return Optional.empty();
    return tenants.findById(new ObjectId(id)).map(t -> {
      var parents = t.getPartyRelationships() == null ? List.<String>of() : t.getPartyRelationships().stream()
          .filter(r -> RelationshipTypeEnum.MASTER_TENANT.getCode().equals(r.getRelationshipTypeCode()) && r.getToPartyId() != null)
          .map(r -> r.getToPartyId().toHexString()).distinct().toList();
      boolean active = !Boolean.TRUE.equals(t.getDeleted()) && (t.getStatus() == null || t.getStatus().getStatusCode() == null
          || Set.of("ACTIVE", "ENABLED").contains(t.getStatus().getStatusCode().toUpperCase(Locale.ROOT)));
      return new TenantInfo(t.getIdAsString(), t.getTenantType() == null ? TenantStructure.STANDALONE : TenantStructure.valueOf(t.getTenantType().name()),
          parents.size() == 1 ? parents.get(0) : null, t.getPartnerType() != null && "ECO".equals(t.getPartnerType().getPartnerTypeCode()), active && parents.size() <= 1);
    });
  }
  public String platformId() {
    return tenants.findByPartnerTypePartnerTypeCode("ECO").filter(t -> !Boolean.TRUE.equals(t.getDeleted()))
        .map(t -> t.getIdAsString()).orElseThrow(() -> new IllegalStateException("Platform Tenant unavailable"));
  }
}
