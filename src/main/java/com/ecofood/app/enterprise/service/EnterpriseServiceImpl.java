package com.ecofood.app.enterprise.service;

import com.ecofood.app.common.dto.EnterpriseCommunicationDetailRequest;
import com.ecofood.app.common.dto.EnterpriseCommunicationRequest;
import com.ecofood.app.common.dto.EnterpriseContactRequest;
import com.ecofood.app.common.dto.EnterpriseCreateRequest;
import com.ecofood.app.common.dto.EnterpriseResponse;
import com.ecofood.app.common.entity.PartyStatus;
import com.ecofood.app.common.service.EnterpriseService;
import com.ecofood.app.enterprise.entity.Communication;
import com.ecofood.app.enterprise.entity.CommunicationDetail;
import com.ecofood.app.enterprise.entity.Contact;
import com.ecofood.app.enterprise.entity.EnterpriseCategory;
import com.ecofood.app.enterprise.entity.EnterpriseEntity;
import com.ecofood.app.enterprise.repository.EnterpriseRepository;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.userACL.entity.RelationshipTypeEnum;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Implements only EnterpriseService (plain, same-module-facing) — never
 * CoreEnterpriseClient directly, per this project's module-boundary pattern
 * (see UserServiceImpl/CoreUserClient).
 */
@Service
@RequiredArgsConstructor
public class EnterpriseServiceImpl implements EnterpriseService {

  private static final String STATUS_ACTIVE = "ACTIVE";

  private final EnterpriseRepository enterpriseRepository;

  @Override
  public EnterpriseResponse createEnterprise(EnterpriseCreateRequest request) {
    List<PartyToPartyRelationship> relations = new ArrayList<>();

    EnterpriseEntity relatedEnterprise = null;
    if (request.getRelatedEnterpriseId() != null) {
      relatedEnterprise =
          enterpriseRepository
              .findById(new ObjectId(request.getRelatedEnterpriseId()))
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Related enterprise not found: " + request.getRelatedEnterpriseId()));
      relations.add(buildRelation(relatedEnterprise, RelationshipTypeEnum.BELONGTO));
    }

    EnterpriseEntity enterprise =
        EnterpriseEntity.builder()
            .partyName(request.getEnterpriseCompanyName())
            .partyStatus(PartyStatus.builder().statusCode(STATUS_ACTIVE).statusDescription(STATUS_ACTIVE).build())
            .enterpriseCategory(
                EnterpriseCategory.builder()
                    .enterpriseCategoryCode(request.getPartnerTypeCode().name())
                    .enterpriseCategoryDescription(request.getPartnerTypeDescription())
                    .build())
            .contact(mapContact(request.getContact()))
            .partyRelationships(relations)
            .build();

    enterpriseRepository.save(enterprise);

    if (relatedEnterprise != null) {
      relatedEnterprise.getPartyRelationships().add(buildRelation(enterprise, RelationshipTypeEnum.OWN));
      enterpriseRepository.save(relatedEnterprise);
    }

    return EnterpriseResponse.builder()
        .enterpriseId(enterprise.getIdAsString())
        .enterpriseCompanyName(enterprise.getPartyName())
        .build();
  }

  private PartyToPartyRelationship buildRelation(EnterpriseEntity target, RelationshipTypeEnum relationshipType) {
    return PartyToPartyRelationship.builder()
        .toPartyId(target.getId())
        .relationshipTypeCode(relationshipType.getCode())
        .relationshipTypeDecription(String.format(relationshipType.getDescription(), target.getPartyName()))
        .relationshipReferenceName(target.getPartyName())
        .build();
  }

  private Contact mapContact(EnterpriseContactRequest request) {
    if (request == null) {
      return null;
    }

    List<Communication> communications =
        request.getCommunications() != null
            ? request.getCommunications().stream().map(this::mapCommunication).toList()
            : new ArrayList<>();

    return Contact.builder()
        .contactPartyName(request.getContactPartyName())
        .contactPartyLastName(request.getContactPartyLastName())
        .contactDepartment(request.getContactDepartment())
        .contactDesignation(request.getContactDesignation())
        .communications(communications)
        .build();
  }

  private Communication mapCommunication(EnterpriseCommunicationRequest request) {
    List<CommunicationDetail> details =
        request.getCommunicationDetails() != null
            ? request.getCommunicationDetails().stream().map(this::mapCommunicationDetail).toList()
            : new ArrayList<>();

    return Communication.builder()
        .communicationChannelName(request.getCommunicationChannelName())
        .preferred(request.isPreferred())
        .communicationDetails(details)
        .build();
  }

  private CommunicationDetail mapCommunicationDetail(EnterpriseCommunicationDetailRequest request) {
    return CommunicationDetail.builder()
        .locationType(request.getLocationType())
        .preferred(request.isPreferred())
        .addressLine1(request.getAddressLine1())
        .addressLine2(request.getAddressLine2())
        .isoCityCode(request.getIsoCityCode())
        .isoCountryCode(request.getIsoCountryCode())
        .postalCode(request.getPostalCode())
        .internationalTelecomCountryCode(request.getInternationalTelecomCountryCode())
        .phoneNumber(request.getPhoneNumber())
        .emailAddress(request.getEmailAddress())
        .build();
  }
}
