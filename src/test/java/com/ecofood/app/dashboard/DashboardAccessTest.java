package com.ecofood.app.dashboard;

import static com.ecofood.app.dashboard.DashboardFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.userACL.service.DashboardAccessService;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.entity.Group;
import com.ecofood.app.userACL.entity.GroupEntity;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.entity.UserGroupEntity;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.enums.*;
import com.ecofood.core.enums.AccessEntityTypeEnum;
import com.ecofood.core.enums.AccessLevelEnum;
import com.ecofood.core.enums.GroupType;
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
  DashboardAccessService access = new DashboardAccessService(mongo, users, tenants); MockedStatic<AuthenticationContext> token;
  @BeforeEach void setup() {
    token = mockStatic(AuthenticationContext.class);
    token.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(USER)); token.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(A));
    token.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_ADMIN")); token.when(AuthenticationContext::getPermissions).thenReturn(List.of(DashboardAccessService.READ));
    var user = new UserEntity(); user.setId(new ObjectId(USER)); user.setPartyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(A)).relationshipTypeCode("TENANT").build()));
    when(mongo.findById(new ObjectId(USER), UserEntity.class)).thenReturn(user);
    when(users.resolveAccess(new ObjectId(USER), new ObjectId(A))).thenReturn(new UserAccessService.UserAccessClaims(List.of("TENANT_ADMIN"), List.of(DashboardAccessService.READ), List.of()));
    when(tenants.propositionReference(A)).thenReturn(tenant(A, false));
  }
  @AfterEach void close() { token.close(); }
  @Test void adminWithoutRuleHasEmptyDataScope() { assertTrue(access.current().tenantScope().isEmpty()); }
  private Group grant(AccessEntityTypeEnum type, AccessLevelEnum level) {
    ObjectId id = new ObjectId();
    var membership = UserGroupEntity.builder().userIdentifier(new ObjectId(USER)).groups(List.of(UserGroupEntity.EmbeddedRestrictionGroup.builder()
        .groupIdentifier(id).tenantIdentifier(new ObjectId(A)).build())).build();
    when(mongo.findOne(any(Query.class),eq(UserGroupEntity.class))).thenReturn(membership);
    var group = Group.builder().groupType(GroupType.ACCESS_CONTROL_GROUP)
        .accessEntityType(type).accessLevel(level).partyRelationships(List.of(PartyToPartyRelationship.builder()
        .relationshipTypeCode("TENANT").toPartyId(new ObjectId(A)).build())).build();
    when(mongo.findById(id,Group.class)).thenReturn(group);
    return group;
  }
  @Test void fullBusinessRuleDoesNotGrantInvoiceData() {
    grant(AccessEntityTypeEnum.BUSINESS_DASHBOARD, AccessLevelEnum.FULL_ACCESS);
    assertEquals(Set.of(A),access.current().tenantScope());
    assertTrue(access.current(AccessEntityTypeEnum.INVOICE_DASHBOARD).tenantScope().isEmpty());
  }
  @Test void combinedRuleGrantsAllThreeDashboardsInItsTenant() {
    grant(AccessEntityTypeEnum.ALL_DASHBOARDS, AccessLevelEnum.FULL_ACCESS);
    for (var type : List.of(AccessEntityTypeEnum.BUSINESS_DASHBOARD, AccessEntityTypeEnum.INVOICE_DASHBOARD, AccessEntityTypeEnum.CUSTOMER_DASHBOARD)) {
      var actor = access.current(type);
      assertEquals(Set.of(A), actor.tenantScope());
      assertNull(actor.entityIds());
    }
  }
  @Test void combinedRuleDoesNotBypassRoleCheck() {
    grant(AccessEntityTypeEnum.ALL_DASHBOARDS, AccessLevelEnum.FULL_ACCESS);
    token.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_USER"));
    assertThrows(ResponseStatusException.class, () -> access.current(AccessEntityTypeEnum.INVOICE_DASHBOARD));
  }
  @Test void combinedRuleDoesNotGrantAnotherTenant() {
    grant(AccessEntityTypeEnum.ALL_DASHBOARDS, AccessLevelEnum.FULL_ACCESS);
    when(tenants.propositionReference(B)).thenReturn(tenant(B,false));
    assertThrows(ResponseStatusException.class, () -> access.current(AccessEntityTypeEnum.INVOICE_DASHBOARD, B));
  }
  @Test void checkedTypesGrantOnlyThoseDashboardsAndUncheckingRevokesAccess() {
    var group = grant(null, AccessLevelEnum.FULL_ACCESS);
    group.setAccessEntityTypes(List.of(AccessEntityTypeEnum.BUSINESS_DASHBOARD, AccessEntityTypeEnum.INVOICE_DASHBOARD));
    assertEquals(Set.of(A), access.current().tenantScope());
    assertEquals(Set.of(A), access.current(AccessEntityTypeEnum.INVOICE_DASHBOARD).tenantScope());
    assertTrue(access.current(AccessEntityTypeEnum.CUSTOMER_DASHBOARD).tenantScope().isEmpty());
    group.setAccessEntityTypes(List.of(AccessEntityTypeEnum.BUSINESS_DASHBOARD));
    assertTrue(access.current(AccessEntityTypeEnum.INVOICE_DASHBOARD).tenantScope().isEmpty());
  }
  @Test void explicitSelectionsOverrideAnyStaleLegacyAllValue() {
    var group = grant(AccessEntityTypeEnum.ALL_DASHBOARDS, AccessLevelEnum.FULL_ACCESS);
    group.setAccessEntityTypes(List.of(AccessEntityTypeEnum.BUSINESS_DASHBOARD));
    assertTrue(access.current(AccessEntityTypeEnum.INVOICE_DASHBOARD).tenantScope().isEmpty());
    group.setAccessEntityTypes(List.of());
    assertTrue(access.current().tenantScope().isEmpty());
  }
  @Test void enterpriseOptionsContainItsChildrenButOnlyGrantedChildrenAreAccessible() {
    var parent = new com.ecofood.core.dto.PropositionTenantReference(A,"Enterprise",ENTERPRISE,PartnerTypeEnum.ENTERPRISE,TenantTypeEnum.MASTER_TENANT,List.of(A,B,OWNER,PRODUCT),List.of(),false);
    when(tenants.propositionReference(A)).thenReturn(parent);
    var relation = new com.ecofood.core.dto.PartyRelationshipResponse(A,"MT","Enterprise");
    when(tenants.propositionReference(B)).thenReturn(new com.ecofood.core.dto.PropositionTenantReference(B,"B",null,PartnerTypeEnum.BRAND,TenantTypeEnum.SUB_TENANT,List.of(B),List.of(relation),false));
    when(tenants.propositionReference(OWNER)).thenReturn(new com.ecofood.core.dto.PropositionTenantReference(OWNER,"C",null,PartnerTypeEnum.BRAND,TenantTypeEnum.SUB_TENANT,List.of(OWNER),List.of(relation),false));
    when(tenants.propositionReference(PRODUCT)).thenReturn(new com.ecofood.core.dto.PropositionTenantReference(PRODUCT,"Other enterprise child",null,PartnerTypeEnum.BRAND,TenantTypeEnum.SUB_TENANT,List.of(PRODUCT),List.of(),false));
    var id = new ObjectId();
    when(mongo.findOne(any(Query.class),eq(UserGroupEntity.class))).thenReturn(UserGroupEntity.builder().userIdentifier(new ObjectId(USER))
        .groups(List.of(UserGroupEntity.EmbeddedRestrictionGroup.builder().groupIdentifier(id).tenantIdentifier(new ObjectId(B)).build())).build());
    when(mongo.findById(id,Group.class)).thenReturn(Group.builder().groupType(GroupType.ACCESS_CONTROL_GROUP).accessEntityType(AccessEntityTypeEnum.BUSINESS_DASHBOARD)
        .accessLevel(AccessLevelEnum.FULL_ACCESS).partyRelationships(List.of(PartyToPartyRelationship.builder().relationshipTypeCode("TENANT").toPartyId(new ObjectId(B)).build())).build());
    var options = access.tenantOptions(AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    assertEquals(List.of(A,B,OWNER),options.stream().map(a -> a.tenant().id()).toList());
    assertTrue(options.get(0).tenantScope().isEmpty()); assertEquals(Set.of(B),options.get(1).tenantScope());
    assertTrue(options.get(2).tenantScope().isEmpty());
    assertEquals(List.of(B),access.authorizedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD).stream().map(a -> a.tenant().id()).toList());
  }
  @Test void rolesUseRequiredSuffixes() {
    assertTrue(DashboardAccessService.managerRole("tenant_admin"));
    assertTrue(DashboardAccessService.managerRole("REGIONAL_DASHBOARD_MANAGER"));
    assertFalse(DashboardAccessService.managerRole("TENANT_USER"));
  }
  @Test void invalidLiveRoleIsDeniedEvenWithTokenAdmin() {
    when(users.resolveAccess(new ObjectId(USER),new ObjectId(A))).thenReturn(new UserAccessService.UserAccessClaims(List.of("TENANT_USER"),List.of(DashboardAccessService.READ),List.of()));
    assertThrows(ResponseStatusException.class,access::current);
  }
}
