package com.ecofood.app.enterprise.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ecofood.core.dto.EnterpriseResponse;
import com.ecofood.core.enums.RelationshipTypeEnum;
import com.ecofood.app.enterprise.entity.EnterpriseEntity;
import com.ecofood.app.enterprise.repository.EnterpriseRepository;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnterpriseQueryServiceImplTest {

  @Mock private EnterpriseRepository enterpriseRepository;

  private EnterpriseQueryServiceImpl enterpriseQueryService;

  @Test
  void dashboardOwnershipAcceptsOwnOrBelongToAndDeduplicatesActiveChildren() {
    var parentId = new ObjectId(); var childId = new ObjectId(); var deletedId = new ObjectId(); var blId = new ObjectId();
    var parent = EnterpriseEntity.builder().partyRelationships(List.of(
        PartyToPartyRelationship.builder().relationshipTypeCode("OW").toPartyId(childId).build(),
        PartyToPartyRelationship.builder().relationshipTypeCode("OW").toPartyId(deletedId).build())).build();
    var child = EnterpriseEntity.builder().build(); child.setId(childId);
    var deleted = EnterpriseEntity.builder().build(); deleted.setId(deletedId); deleted.setDeleted(true);
    var bl = EnterpriseEntity.builder().build(); bl.setId(blId);
    when(enterpriseRepository.findById(parentId)).thenReturn(Optional.of(parent));
    when(enterpriseRepository.findAllById(List.of(childId,deletedId))).thenReturn(List.of(child,deleted));
    when(enterpriseRepository.findDashboardChildren(parentId)).thenReturn(List.of(child,bl));
    var service = new EnterpriseQueryServiceImpl(enterpriseRepository);
    assertThat(service.dashboardChildEnterpriseIds(parentId.toHexString())).containsExactly(childId.toHexString(),blId.toHexString());
  }

  @Test
  void deletedEnterpriseCannotExposeDashboardChildren() {
    var parentId = new ObjectId(); var parent = EnterpriseEntity.builder().build(); parent.setDeleted(true);
    when(enterpriseRepository.findById(parentId)).thenReturn(Optional.of(parent));
    assertThat(new EnterpriseQueryServiceImpl(enterpriseRepository).dashboardChildEnterpriseIds(parentId.toHexString())).isEmpty();
    org.mockito.Mockito.verify(enterpriseRepository,org.mockito.Mockito.never()).findDashboardChildren(parentId);
  }

  @Test
  void returnsTheDtoWhenFound() {
    enterpriseQueryService = new EnterpriseQueryServiceImpl(enterpriseRepository);
    EnterpriseEntity enterprise = EnterpriseEntity.builder().partyName("Acme Co").build();
    ObjectId id = new ObjectId();
    enterprise.setId(id);
    when(enterpriseRepository.findById(id)).thenReturn(Optional.of(enterprise));

    EnterpriseResponse result = enterpriseQueryService.getById(id.toHexString());

    assertThat(result.getEnterpriseCompanyName()).isEqualTo("Acme Co");
    assertThat(result.getEnterpriseId()).isEqualTo(id.toHexString());
  }

  @Test
  void throwsWhenNotFound() {
    enterpriseQueryService = new EnterpriseQueryServiceImpl(enterpriseRepository);
    ObjectId id = new ObjectId();
    when(enterpriseRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> enterpriseQueryService.getById(id.toHexString()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(id.toHexString());
  }

  @Test
  void ownedEnterpriseIdsReturnsOnlyTheOwnRelatedIds() {
    enterpriseQueryService = new EnterpriseQueryServiceImpl(enterpriseRepository);
    ObjectId id = new ObjectId();
    ObjectId child = new ObjectId();
    ObjectId unrelated = new ObjectId();
    EnterpriseEntity enterprise =
        EnterpriseEntity.builder()
            .partyName("Acme Co")
            .partyRelationships(
                List.of(
                    PartyToPartyRelationship.builder()
                        .toPartyId(child)
                        .relationshipTypeCode(RelationshipTypeEnum.OWN.getCode())
                        .build(),
                    PartyToPartyRelationship.builder()
                        .toPartyId(unrelated)
                        .relationshipTypeCode(RelationshipTypeEnum.BELONGTO.getCode())
                        .build()))
            .build();
    enterprise.setId(id);
    when(enterpriseRepository.findById(id)).thenReturn(Optional.of(enterprise));

    assertThat(enterpriseQueryService.ownedEnterpriseIds(id.toHexString()))
        .containsExactly(child.toHexString());
  }

  @Test
  void ownedEnterpriseIdsReturnsEmptyWhenNotFound() {
    enterpriseQueryService = new EnterpriseQueryServiceImpl(enterpriseRepository);
    ObjectId id = new ObjectId();
    when(enterpriseRepository.findById(id)).thenReturn(Optional.empty());

    assertThat(enterpriseQueryService.ownedEnterpriseIds(id.toHexString())).isEmpty();
  }
}
