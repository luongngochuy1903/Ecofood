package com.ecofood.app.userACL.service;

import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.userACL.entity.UserCategory;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.repository.UserRepository;
import com.ecofood.core.enums.UserCategoryEnum;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/** ACL-owned adapter: customer module does not query app_user directly. */
@Service
@RequiredArgsConstructor
public class CustomerAccountLookupService {
  private final UserRepository userRepository;

  public CustomerAccount resolve(ObjectId userId, ObjectId tenantId) {
    UserEntity user = userRepository.findById(userId)
        .orElseThrow(() -> new ForbiddenException("Customer account unavailable"));

    UserCategory category = user.getUserCategory();
    if (Boolean.TRUE.equals(user.getDeleted())
        || category == null
        || !UserCategoryEnum.CUSTOMER.getCode().equals(category.getUserCategoryCode())) {
      throw new ForbiddenException("Customer access required");
    }

    boolean belongsToTenant = user.getPartyRelationships() != null
        && user.getPartyRelationships().stream()
            .map(PartyToPartyRelationship::getToPartyId)
            .anyMatch(tenantId::equals);
    if (!belongsToTenant) {
      throw new ForbiddenException("Customer does not belong to the current tenant");
    }

    return new CustomerAccount(userId, tenantId,
        Objects.toString(user.getEmail(), ""),
        Objects.toString(user.getFirstName(), ""),
        Objects.toString(user.getLastName(), ""));
  }

  public record CustomerAccount(ObjectId userId, ObjectId tenantId,
                                String email, String firstName, String lastName) {}
}
