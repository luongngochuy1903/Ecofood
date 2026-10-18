package com.ecofood.app.userACL.service;

import com.ecofood.core.dto.PartyRelationshipResponse;
import com.ecofood.core.dto.UserQueryResponse;
import com.ecofood.core.service.UserQueryService;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Read-only user lookups for OTHER modules — auth must never import
 * userACL.repository.UserRepository directly, only CoreUserQueryClient
 * (common.client), which delegates to this bean via the UserQueryService
 * interface.
 */
@Service
@RequiredArgsConstructor
public class UserQueryServiceImpl implements UserQueryService {

  private final UserRepository userRepository;

  @Override
  public UserQueryResponse getById(String userId) {
    return toResponse(
        userRepository
            .findById(new ObjectId(userId))
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId)));
  }

  @Override
  public UserQueryResponse getByUsername(String username) {
    return toResponse(
        userRepository
            .findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("No local user found for username=" + username)));
  }

  private UserQueryResponse toResponse(UserEntity user) {
    return UserQueryResponse.builder()
        .id(user.getIdAsString())
        .username(user.getUsername())
        .email(user.getEmail())
        .externalLoginId(user.getExternalLoginId())
        .partyRelationships(user.getPartyRelationships().stream().map(this::toRelationResponse).toList())
        .build();
  }

  private PartyRelationshipResponse toRelationResponse(PartyToPartyRelationship relation) {
    return PartyRelationshipResponse.builder()
        .toPartyId(relation.getToPartyId().toHexString())
        .relationshipTypeCode(relation.getRelationshipTypeCode())
        .relationshipReferenceName(relation.getRelationshipReferenceName())
        .build();
  }
}
