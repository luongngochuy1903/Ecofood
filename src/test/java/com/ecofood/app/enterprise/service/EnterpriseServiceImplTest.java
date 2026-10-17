package com.ecofood.app.enterprise.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecofood.core.dto.EnterpriseCommunicationDetailRequest;
import com.ecofood.core.dto.EnterpriseCommunicationRequest;
import com.ecofood.core.dto.EnterpriseContactRequest;
import com.ecofood.core.dto.EnterpriseCreateRequest;
import com.ecofood.core.dto.EnterpriseResponse;
import com.ecofood.app.enterprise.entity.EnterpriseEntity;
import com.ecofood.app.enterprise.repository.EnterpriseRepository;
import com.ecofood.core.enums.PartnerTypeEnum;
import java.util.List;
import java.util.Optional;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnterpriseServiceImplTest {

  @Mock private EnterpriseRepository enterpriseRepository;

  private EnterpriseServiceImpl enterpriseService;

  @BeforeEach
  void setUp() {
    enterpriseService = new EnterpriseServiceImpl(enterpriseRepository);
  }

  // Mirrors Spring Data Mongo's real save() behavior: mutates and returns the
  // same entity with an auto-assigned id if it didn't already have one. Only
  // stubbed in tests that actually reach a save() call — strict stubbing
  // flags it as unnecessary otherwise (e.g. the not-found test, which throws
  // before ever calling save).
  private void stubSaveAssignsId() {
    when(enterpriseRepository.save(any(EnterpriseEntity.class)))
        .thenAnswer(
            invocation -> {
              EnterpriseEntity entity = invocation.getArgument(0);
              if (entity.getId() == null) {
                entity.setId(new ObjectId());
              }
              return entity;
            });
  }

  private EnterpriseCreateRequest basicRequest() {
    EnterpriseCreateRequest request = new EnterpriseCreateRequest();
    request.setEnterpriseCompanyName("Acme Foods");
    request.setPartnerTypeCode(PartnerTypeEnum.ENTERPRISE);
    request.setPartnerTypeDescription("Enterprise Partner");
    return request;
  }

  @Test
  void createsEnterpriseWithDerivedStatusAndCategory() {
    stubSaveAssignsId();
    EnterpriseResponse response = enterpriseService.createEnterprise(basicRequest());

    assertThat(response.getEnterpriseCompanyName()).isEqualTo("Acme Foods");
    assertThat(response.getEnterpriseId()).isNotBlank();

    ArgumentCaptorHelper.captureSaved(enterpriseRepository)
        .satisfies(
            saved -> {
              assertThat(saved.getPartyName()).isEqualTo("Acme Foods");
              assertThat(saved.getPartyStatus().getStatusCode()).isEqualTo("ACTIVE");
              assertThat(saved.getEnterpriseCategory().getEnterpriseCategoryCode()).isEqualTo("ENTERPRISE");
              assertThat(saved.getEnterpriseCategory().getEnterpriseCategoryDescription())
                  .isEqualTo("Enterprise Partner");
              assertThat(saved.getContact()).isNull();
              assertThat(saved.getPartyRelationships()).isEmpty();
            });
  }

  @Test
  void mapsFullContactTreeWhenProvided() {
    stubSaveAssignsId();
    EnterpriseCreateRequest request = basicRequest();
    EnterpriseCommunicationDetailRequest detail = new EnterpriseCommunicationDetailRequest();
    detail.setPhoneNumber("+1234567890");
    detail.setEmailAddress("ops@acme.com");
    EnterpriseCommunicationRequest communication = new EnterpriseCommunicationRequest();
    communication.setCommunicationChannelName("office");
    communication.setPreferred(true);
    communication.setCommunicationDetails(List.of(detail));
    EnterpriseContactRequest contact = new EnterpriseContactRequest();
    contact.setContactPartyName("John");
    contact.setContactPartyLastName("Smith");
    contact.setCommunications(List.of(communication));
    request.setContact(contact);

    enterpriseService.createEnterprise(request);

    ArgumentCaptorHelper.captureSaved(enterpriseRepository)
        .satisfies(
            saved -> {
              assertThat(saved.getContact().getContactPartyName()).isEqualTo("John");
              assertThat(saved.getContact().getContactPartyLastName()).isEqualTo("Smith");
              assertThat(saved.getContact().getCommunications()).hasSize(1);
              assertThat(saved.getContact().getCommunications().get(0).getCommunicationChannelName())
                  .isEqualTo("office");
              assertThat(saved.getContact().getCommunications().get(0).isPreferred()).isTrue();
              assertThat(saved.getContact().getCommunications().get(0).getCommunicationDetails()).hasSize(1);
              assertThat(
                      saved.getContact().getCommunications().get(0).getCommunicationDetails().get(0).getPhoneNumber())
                  .isEqualTo("+1234567890");
            });
  }

  @Test
  void relatedEnterpriseIdAddsBelongtoOnNewEntityAndOwnOnParent() {
    stubSaveAssignsId();
    EnterpriseEntity masterEnterprise = EnterpriseEntity.builder().partyName("Master Co").build();
    ObjectId masterId = new ObjectId();
    masterEnterprise.setId(masterId);
    when(enterpriseRepository.findById(masterId)).thenReturn(Optional.of(masterEnterprise));

    EnterpriseCreateRequest request = basicRequest();
    request.setPartnerTypeCode(PartnerTypeEnum.REGIONAL_ENTERPRISE);
    request.setRelatedEnterpriseId(masterId.toHexString());

    enterpriseService.createEnterprise(request);

    // The new (regional) entity gets a BELONGTO relation to the master.
    verify(enterpriseRepository)
        .save(
            org.mockito.ArgumentMatchers.argThat(
                entity ->
                    entity != masterEnterprise
                        && entity.getPartyRelationships().size() == 1
                        && entity.getPartyRelationships().get(0).getRelationshipTypeCode().equals("BL")
                        && entity.getPartyRelationships().get(0).getToPartyId().equals(masterId)
                        && entity
                            .getPartyRelationships()
                            .get(0)
                            .getRelationshipTypeDecription()
                            .equals("Belongs to Master Co")));

    // The master entity is updated with the reciprocal OWN relation back to
    // the new entity — an actual second save, not a one-sided link.
    assertThat(masterEnterprise.getPartyRelationships()).hasSize(1);
    assertThat(masterEnterprise.getPartyRelationships().get(0).getRelationshipTypeCode()).isEqualTo("OW");
    verify(enterpriseRepository).save(masterEnterprise);
  }

  @Test
  void throwsWhenRelatedEnterpriseIdDoesNotExistAndNeverSaves() {
    ObjectId missingId = new ObjectId();
    when(enterpriseRepository.findById(missingId)).thenReturn(Optional.empty());

    EnterpriseCreateRequest request = basicRequest();
    request.setRelatedEnterpriseId(missingId.toHexString());

    assertThatThrownBy(() -> enterpriseService.createEnterprise(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(missingId.toHexString());

    verify(enterpriseRepository, never()).save(any());
  }

  /** Small local helper so the two assertion-heavy tests above stay readable. */
  private static final class ArgumentCaptorHelper {
    static org.assertj.core.api.ObjectAssert<EnterpriseEntity> captureSaved(EnterpriseRepository repository) {
      org.mockito.ArgumentCaptor<EnterpriseEntity> captor = org.mockito.ArgumentCaptor.forClass(EnterpriseEntity.class);
      verify(repository).save(captor.capture());
      return assertThat(captor.getValue());
    }
  }
}
