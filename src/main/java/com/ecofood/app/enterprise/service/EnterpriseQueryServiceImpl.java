package com.ecofood.app.enterprise.service;

import com.ecofood.core.dto.EnterpriseResponse;
import com.ecofood.core.enums.RelationshipTypeEnum;
import com.ecofood.core.service.EnterpriseQueryService;
import com.ecofood.app.enterprise.entity.EnterpriseEntity;
import com.ecofood.app.enterprise.repository.EnterpriseRepository;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Read-only enterprise lookups for OTHER modules — userACL must never import
 * enterprise.repository.EnterpriseRepository directly, only
 * CoreEnterpriseQueryClient. Kept separate from EnterpriseServiceImpl (which
 * owns creation) — mirrors TenantQueryServiceImpl/TenantServiceImpl's split.
 */
@Service
@RequiredArgsConstructor
public class EnterpriseQueryServiceImpl implements EnterpriseQueryService {

  private final EnterpriseRepository enterpriseRepository;

  @Override
  public EnterpriseResponse getById(String enterpriseId) {
    EnterpriseEntity enterprise =
        enterpriseRepository
            .findById(new ObjectId(enterpriseId))
            .orElseThrow(() -> new IllegalArgumentException("Enterprise not found: " + enterpriseId));
    return EnterpriseResponse.builder()
        .enterpriseId(enterprise.getIdAsString())
        .enterpriseCompanyName(enterprise.getPartyName())
        .build();
  }

  @Override
  public List<String> ownedEnterpriseIds(String enterpriseId) {
    if (enterpriseId == null || !ObjectId.isValid(enterpriseId)) return List.of();
    return enterpriseRepository
        .findById(new ObjectId(enterpriseId))
        .map(EnterpriseEntity::getPartyRelationships)
        .map(
            relationships ->
                relationships.stream()
                    .filter(r -> RelationshipTypeEnum.OWN.getCode().equals(r.getRelationshipTypeCode()))
                    .map(r -> r.getToPartyId() == null ? null : r.getToPartyId().toHexString())
                    .filter(Objects::nonNull)
                    .toList())
        .orElse(List.of());
  }

  @Override
  public List<String> dashboardChildEnterpriseIds(String enterpriseId) {
    if (enterpriseId == null || !ObjectId.isValid(enterpriseId)) return List.of();
    var parent = enterpriseRepository.findById(new ObjectId(enterpriseId)).orElse(null);
    if (parent == null || Boolean.TRUE.equals(parent.getDeleted())) return List.of();
    var ids = new java.util.LinkedHashSet<String>();
    if (parent.getPartyRelationships() != null) {
      var owned = parent.getPartyRelationships().stream()
          .filter(r -> RelationshipTypeEnum.OWN.getCode().equals(r.getRelationshipTypeCode()))
          .map(r -> r.getToPartyId()).filter(Objects::nonNull).toList();
      enterpriseRepository.findAllById(owned).stream().filter(e -> !Boolean.TRUE.equals(e.getDeleted()))
          .forEach(e -> ids.add(e.getIdAsString()));
    }
    enterpriseRepository.findDashboardChildren(new ObjectId(enterpriseId))
        .forEach(e -> ids.add(e.getIdAsString()));
    return List.copyOf(ids);
  }
}
