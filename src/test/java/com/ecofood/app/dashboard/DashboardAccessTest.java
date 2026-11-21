package com.ecofood.app.dashboard;

import static com.ecofood.app.dashboard.DashboardFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.userACL.entity.*;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.enums.*;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.UserAccessService;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

class DashboardAccessTest {
  MongoTemplate mongo = mock(MongoTemplate.class); UserAccessService users = mock(UserAccessService.class); CoreTenantQueryClient tenants = mock(CoreTenantQueryClient.class);
  DashboardAccess access = new DashboardAccess(mongo, users, tenants); MockedStatic<AuthenticationContext> token;
  @BeforeEach void setup() {
    token = mockStatic(AuthenticationContext.class);
    token.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(USER)); token.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(A));
    token.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_ADMIN")); token.when(AuthenticationContext::getPermissions).thenReturn(List.of(DashboardAccess.READ));
    var user = new UserEntity(); user.setId(new ObjectId(USER)); user.setPartyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(A)).relationshipTypeCode("TENANT").build()));
    when(mongo.findById(new ObjectId(USER), UserEntity.class)).thenReturn(user);
    when(users.resolveAccess(new ObjectId(USER), new ObjectId(A))).thenReturn(new UserAccessService.UserAccessClaims(List.of("TENANT_ADMIN"), List.of(DashboardAccess.READ), List.of()));
    when(tenants.propositionReference(A)).thenReturn(tenant(A, false));
  }
  @AfterEach void close() { token.close(); }
  @Test void tenantAdminIsRestrictedToCurrentVerifiedTenant() { var actor = access.current(); assertEquals(Set.of(A), actor.tenantScope()); assertFalse(actor.partners()); }
  @Test void staleTokenPermissionAndMissingMembershipAreRejected() {
    token.when(AuthenticationContext::getPermissions).thenReturn(List.of()); assertThrows(ResponseStatusException.class, access::current);
    token.when(AuthenticationContext::getPermissions).thenReturn(List.of(DashboardAccess.READ)); when(mongo.findById(new ObjectId(USER), UserEntity.class)).thenReturn(null); assertThrows(ResponseStatusException.class, access::current);
  }
  @Test void livePermissionRevocationTakesEffectWithoutWaitingForTokenExpiry() {
    when(users.resolveAccess(any(), any())).thenReturn(new UserAccessService.UserAccessClaims(List.of("TENANT_ADMIN"), List.of(), List.of())); assertThrows(ResponseStatusException.class, access::current);
  }
  private void grant(AccessLevelEnum level, AccessEntityTypeEnum type) {
    ObjectId groupId = new ObjectId(); var membership = new UserGroupEntity(); membership.setGroups(List.of(UserGroupEntity.EmbeddedRestrictionGroup.builder().tenantIdentifier(new ObjectId(A)).groupIdentifier(groupId).build()));
    when(mongo.findOne(any(Query.class), eq(UserGroupEntity.class))).thenReturn(membership);
    var group = new Group(); group.setId(groupId); group.setGroupType(GroupType.ACCESS_CONTROL_GROUP); group.setAccessLevel(level); group.setAccessEntityType(type);
    group.setPartyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(A)).relationshipTypeCode("TENANT").build())); when(mongo.findById(groupId, Group.class)).thenReturn(group);
    when(mongo.find(any(Query.class), eq(GroupEntity.class))).thenReturn(List.of());
  }
  @Test void invoiceLimitedDarCannotExposeWholeMonthlyAggregate() { grant(AccessLevelEnum.LIMITED_ACCESS, AccessEntityTypeEnum.Dashboard_bills); assertThrows(ResponseStatusException.class, access::current); }
  @Test void employeeNeedsExplicitFullDashboardDar() {
    token.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_EMPLOYEE")); when(users.resolveAccess(any(), any())).thenReturn(new UserAccessService.UserAccessClaims(List.of("TENANT_EMPLOYEE"), List.of(DashboardAccess.READ), List.of()));
    assertThrows(ResponseStatusException.class, access::current); grant(AccessLevelEnum.FULL_ACCESS, AccessEntityTypeEnum.Dashboard_bills); assertEquals(Set.of(A), access.current().tenantScope());
  }
  @Test void limitedProductDarRestrictsPropositionsBySourceProduct() { grant(AccessLevelEnum.LIMITED_ACCESS, AccessEntityTypeEnum.Product); assertEquals(Set.of(), access.current().productIds()); }
  @Test void enterprisePartnerScopeRequiresExtraPermissionAndTrustedHierarchy() {
    when(tenants.propositionReference(A)).thenReturn(tenant(OWNER, true));
    assertEquals(Set.of(A), access.current().tenantScope());
    token.when(AuthenticationContext::getPermissions).thenReturn(List.of(DashboardAccess.READ, DashboardAccess.PARTNERS));
    when(users.resolveAccess(any(), any())).thenReturn(new UserAccessService.UserAccessClaims(List.of("TENANT_ADMIN"), List.of(DashboardAccess.READ, DashboardAccess.PARTNERS), List.of()));
    assertEquals(Set.of(A, OWNER, B), access.current().tenantScope());
  }
}
