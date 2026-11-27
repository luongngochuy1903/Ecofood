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
