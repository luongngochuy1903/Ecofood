package com.ecofood.app.customer.security;

import com.ecofood.app.customer.entity.CustomerUser;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.CustomerIdentityScope;
import com.ecofood.core.security.AuthenticationContext;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CustomerAccess {
  private final MongoTemplate mongo;
  private final CoreTenantQueryClient tenants;
  public record Actor(String userId, String tenantId, CustomerUser user)
      implements CustomerIdentityScope {}
  public Actor current() {
    String id;
    String tenant;
    try { id = AuthenticationContext.getUserId().toHexString(); tenant = AuthenticationContext.getTenantId().toHexString(); }
    catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer authentication required"); }
    CustomerUser user = mongo.findById(new ObjectId(id), CustomerUser.class);
    if (user == null || Boolean.TRUE.equals(user.getDeleted()) || user.getUserCategory() == null
        || !"CUSTOMER".equals(user.getUserCategory().getUserCategoryCode()) || user.getExternalLoginId() == null || user.getCustomerEmailVerifiedAt() == null
        || user.getEmail() == null || !CustomerReceiptTokens.hash(user.getEmail().trim().toLowerCase(java.util.Locale.ROOT)).equals(user.getCustomerVerifiedEmailHash())
        || user.getPartyRelationships() == null || user.getPartyRelationships().stream().noneMatch(r ->
          "TENANT".equals(r.getRelationshipTypeCode()) && new ObjectId(tenant).equals(r.getToPartyId()))) throw denied();
    if (tenants.propositionReference(tenant).deleted()) throw denied();
    return new Actor(id, tenant, user);
  }
  public static ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer account and verified Tenant membership required"); }
}
