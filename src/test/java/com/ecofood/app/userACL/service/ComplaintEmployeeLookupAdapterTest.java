package com.ecofood.app.userACL.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.repository.*;
import com.ecofood.core.client.CoreUserAccessClient;
import com.ecofood.core.enums.RelationshipTypeEnum;
import com.ecofood.core.service.UserAccessService.UserAccessClaims;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;

class ComplaintEmployeeLookupAdapterTest {
  final ObjectId tenant = new ObjectId(), admin = new ObjectId(), issuer = new ObjectId();
  UserRepository users = mock(UserRepository.class);
  UserRoleRepository roles = mock(UserRoleRepository.class);
  CoreUserAccessClient access = mock(CoreUserAccessClient.class);
  ComplaintEmployeeLookupAdapter adapter = new ComplaintEmployeeLookupAdapter(users, access, roles);
  UserEntity user(ObjectId id) {
    var u = UserEntity.builder().externalLoginId("keycloak-subject").userCategory(UserCategory.builder().userCategoryCode("PARTNER").build())
        .partyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(tenant).relationshipTypeCode(RelationshipTypeEnum.TENANT.getCode()).build())).build();
    u.setId(id); return u;
  }
  UserRole assignment(ObjectId id, String code) {
    return UserRole.builder().userId(id).tenantId(tenant).roles(List.of(UserRole.EmbeddedRole.builder().code(code).build())).build();
  }
  @BeforeEach void setup() {
    when(users.findById(admin)).thenReturn(Optional.of(user(admin))); when(users.findById(issuer)).thenReturn(Optional.of(user(issuer)));
    when(roles.findAllByTenantId(tenant)).thenReturn(List.of(assignment(admin, "TENANT_ADMIN"), assignment(issuer, "TENANT_EMPLOYEE")));
    when(access.resolveAccess(admin, tenant)).thenReturn(new UserAccessClaims(List.of("TENANT_ADMIN"), List.of("COMPLAINT_READ", "COMPLAINT_PROCESS"), List.of("TENANT")));
    when(access.resolveAccess(issuer, tenant)).thenReturn(new UserAccessClaims(List.of("TENANT_EMPLOYEE"), List.of("COMPLAINT_READ", "COMPLAINT_PROCESS", "COMPLAINT_ASSIGN"), List.of("SYSTEM")));
  }
  @Test void highestTenantAuthorityIsAdminNotIssuerPermissionCountOrRoleLevel() {
    assertTrue(adapter.invoiceAuthorityRouting()); assertEquals(Set.of(admin.toHexString()), adapter.finalAuthorities(tenant.toHexString()));
    verify(roles).findAllByTenantId(tenant); assertFalse(adapter.finalAuthorities(tenant.toHexString()).contains(issuer.toHexString()));
  }
  @Test void deletedCustomerAndForeignTenantAssignmentsDoNotBecomeAuthorities() {
    var foreign = assignment(admin, "TENANT_ADMIN"); foreign.setTenantId(new ObjectId());
    var deleted = assignment(issuer, "TENANT_ADMIN"); deleted.setDeleted(true);
    when(roles.findAllByTenantId(tenant)).thenReturn(List.of(foreign, deleted)); assertTrue(adapter.finalAuthorities(tenant.toHexString()).isEmpty());
    when(roles.findAllByTenantId(tenant)).thenReturn(List.of(assignment(admin, "TENANT_ADMIN")));
    var customer = user(admin); customer.setCustomerIdentityIssuer("https://identity.test"); when(users.findById(admin)).thenReturn(Optional.of(customer));
    assertTrue(adapter.finalAuthorities(tenant.toHexString()).isEmpty());
  }
  @Test void multipleAdminsAreReturnedAsPeersAndRemovingAdminRoleTakesEffectImmediately() {
    when(roles.findAllByTenantId(tenant)).thenReturn(List.of(assignment(admin, "TENANT_ADMIN"), assignment(issuer, "MASTER_ADMIN")));
    when(access.resolveAccess(issuer, tenant)).thenReturn(new UserAccessClaims(List.of("MASTER_ADMIN"), List.of("COMPLAINT_READ"), List.of("TENANT")));
    assertEquals(Set.of(admin.toHexString(), issuer.toHexString()), adapter.finalAuthorities(tenant.toHexString()));
    when(access.resolveAccess(admin, tenant)).thenReturn(new UserAccessClaims(List.of("TENANT_EMPLOYEE"), List.of(), List.of()));
    assertEquals(Set.of(issuer.toHexString()), adapter.finalAuthorities(tenant.toHexString()));
  }
}
