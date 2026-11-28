package com.ecofood.app.dashboard;

import static com.ecofood.app.dashboard.DashboardFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.entity.Group;
import com.ecofood.app.userACL.service.*;
import com.ecofood.core.client.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.UserAccessService;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class DashboardHierarchyAccessTest {
  private static final String REGIONAL_USER = "888888888888888888888888", REGIONAL_ENTERPRISE = "999999999999999999999999";
  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final UserAccessService users = mock(UserAccessService.class);
  private final CoreTenantQueryClient tenants = mock(CoreTenantQueryClient.class);
  private final CoreEnterpriseQueryClient enterprises = mock(CoreEnterpriseQueryClient.class);
  private final DashboardAccessService access = new DashboardAccessService(mongo, users, tenants, new DashboardTenantHierarchy(tenants, enterprises));
  private final Map<ObjectId, UserGroupEntity> memberships = new HashMap<>();
  private final Map<ObjectId, Group> groups = new HashMap<>();
  private MockedStatic<AuthenticationContext> token;
  private UserEntity regionalUser;

  @BeforeEach void setup() {
    token = mockStatic(AuthenticationContext.class);
    token.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(USER));
    token.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(A));
    token.when(AuthenticationContext::getRoles).thenReturn(List.of("ENTERPRISE_ADMIN"));
    when(users.resolveAccess(new ObjectId(USER),new ObjectId(A))).thenReturn(new UserAccessService.UserAccessClaims(List.of("ENTERPRISE_ADMIN"),List.of(),List.of()));
    when(mongo.findById(new ObjectId(USER),UserEntity.class)).thenReturn(user(USER,A));
    regionalUser = user(REGIONAL_USER,B);
    when(mongo.find(any(Query.class),eq(UserEntity.class))).thenReturn(List.of(regionalUser));
    when(mongo.findOne(any(Query.class),eq(UserGroupEntity.class))).thenAnswer(invocation ->
        memberships.get(((Query) invocation.getArgument(0)).getQueryObject().get("userIdentifier")));
    when(mongo.findById(any(ObjectId.class),eq(Group.class))).thenAnswer(invocation -> groups.get(invocation.getArgument(0)));
    var root = ref(A,PartnerTypeEnum.ENTERPRISE,ENTERPRISE,null);
    var regional = ref(B,PartnerTypeEnum.REGIONAL_ENTERPRISE,REGIONAL_ENTERPRISE,null); // No MT: onboarding uses OW/BL.
    var direct = ref(OWNER,PartnerTypeEnum.BRAND,null,A);
    var indirect = ref(PROP,PartnerTypeEnum.BRAND,null,B);
    var eco = ref(PRODUCT,PartnerTypeEnum.ECOSYSTEM_PARTNER,null,B);
    when(tenants.propositionReference(A)).thenReturn(root);
    when(tenants.propositionReferences()).thenReturn(List.of(root,regional,direct,indirect,eco));
    when(enterprises.dashboardChildEnterpriseIds(ENTERPRISE)).thenReturn(List.of(REGIONAL_ENTERPRISE));
  }
  @AfterEach void close() { token.close(); }

  @ParameterizedTest
  @EnumSource(value=AccessEntityTypeEnum.class,names={"BUSINESS_DASHBOARD","INVOICE_DASHBOARD","CUSTOMER_DASHBOARD"})
  void followsBothEdgesWithoutRequiringRootUserGrantToGrandchild(AccessEntityTypeEnum type) {
    grant(USER,OWNER,type); grant(USER,B,type); grant(REGIONAL_USER,PROP,type);
    assertEquals(Set.of(OWNER,B,PROP),ids(access.scopedTenants(type,A)));
    assertEquals(Set.of(B,PROP),ids(access.scopedTenants(type,B)));
    assertEquals(Set.of(PROP),access.current(type,PROP).tenantScope());
    assertTrue(access.current(type,PRODUCT).tenantScope().isEmpty());
    var parentOption = access.selectableTenants(type).stream().filter(o -> o.id().equals(A)).findFirst().orElseThrow();
    assertTrue(parentOption.accessible()); // Own-data DAR is optional for an aggregate container.
    assertEquals(Set.of(OWNER,B,PROP),new HashSet<>(parentOption.scopeTenantIds()));
  }
  @Test void missingFirstEdgeCannotBeBypassedByDirectGrandchildGrant() {
    grant(REGIONAL_USER,PROP,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    grant(USER,PROP,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    assertTrue(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,A).isEmpty());
    assertTrue(access.current(AccessEntityTypeEnum.BUSINESS_DASHBOARD,PROP).tenantScope().isEmpty());
  }
  @ParameterizedTest
  @EnumSource(value=AccessEntityTypeEnum.class,names={"BUSINESS_DASHBOARD","INVOICE_DASHBOARD"})
  void dashboardFiltersOnlyOfferOwnTenantAndDirectChildrenWhileRegionalSelectionIncludesItsChildren(AccessEntityTypeEnum type) {
    grant(USER,B,type);
    grant(REGIONAL_USER,PROP,type);
    var options = access.selectableTenants(type);
    assertEquals(Set.of(A,B,OWNER),options.stream().map(com.ecofood.core.dto.DashboardDtos.Option::id).collect(java.util.stream.Collectors.toSet()));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> access.scopedTenants(type,PROP));
    assertEquals(Set.of(B,PROP),ids(access.scopedTenants(type,B)));
    assertEquals(Set.of(B,PROP),ids(access.scopedTenants(type,A)));
    assertEquals(Set.of(PROP),access.current(type,PROP).tenantScope()); // Detail of an aggregated child remains readable.
  }
  @Test void regionalSessionCanSelectItsOwnDirectBrand() {
    var regional = tenants.propositionReferences().stream().filter(t -> t.id().equals(B)).findFirst().orElseThrow();
    token.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(B));
    when(mongo.findById(new ObjectId(USER),UserEntity.class)).thenReturn(user(USER,B));
    when(users.resolveAccess(new ObjectId(USER),new ObjectId(B))).thenReturn(new UserAccessService.UserAccessClaims(List.of("ENTERPRISE_ADMIN"),List.of(),List.of()));
    when(tenants.propositionReference(B)).thenReturn(regional);
    grant(USER,PROP,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    assertEquals(Set.of(B,PROP,PRODUCT),access.selectableTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD).stream()
        .map(com.ecofood.core.dto.DashboardDtos.Option::id).collect(java.util.stream.Collectors.toSet()));
    assertEquals(Set.of(PROP),ids(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,PROP)));
  }
  @Test void missingSecondEdgeAndOtherDashboardTypeCannotGrantGrandchild() {
    grant(USER,B,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    grant(REGIONAL_USER,PROP,AccessEntityTypeEnum.INVOICE_DASHBOARD);
    assertEquals(Set.of(B),ids(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,A)));
    assertTrue(access.scopedTenants(AccessEntityTypeEnum.INVOICE_DASHBOARD,A).isEmpty());
  }
  @Test void deletingRuleOrRemovingRegionalUserTenantAssignmentRevokesDescendant() {
    grant(USER,B,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    var downstream = grant(REGIONAL_USER,PROP,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    assertEquals(Set.of(B,PROP),ids(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,A)));
    downstream.setDeleted(true);
    assertEquals(Set.of(B),ids(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,A)));
    downstream.setDeleted(false); regionalUser.setPartyRelationships(List.of());
    assertEquals(Set.of(B),ids(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,A)));
  }
  @Test void limitedIntermediateScopeIsNeverWidenedByFullDownstreamRule() {
    var upstream = grant(USER,B,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    upstream.setAccessLevel(AccessLevelEnum.LIMITED_ACCESS);
    var entities = GroupEntity.builder().groupIdentifier(upstream.getId()).accessEntityType(AccessEntityTypeEnum.BUSINESS_DASHBOARD)
        .entityIds(List.of(new ObjectId(PRODUCT))).build();
    when(mongo.find(any(Query.class),eq(GroupEntity.class))).thenReturn(List.of(entities));
    grant(REGIONAL_USER,PROP,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    assertEquals(Set.of(PRODUCT),access.current(AccessEntityTypeEnum.BUSINESS_DASHBOARD,PROP).entityIds());
  }
  @Test void duplicateRelationshipsAndCyclesDoNotDuplicateTenantsOrGrantOwnData() {
    var root = ref(A,PartnerTypeEnum.ENTERPRISE,ENTERPRISE,B);
    var regional = ref(B,PartnerTypeEnum.REGIONAL_ENTERPRISE,REGIONAL_ENTERPRISE,A);
    when(tenants.propositionReference(A)).thenReturn(root);
    when(tenants.propositionReferences()).thenReturn(List.of(root,regional,ref(PROP,PartnerTypeEnum.BRAND,null,B)));
    grant(USER,B,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    grant(REGIONAL_USER,PROP,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    grant(REGIONAL_USER,A,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    var scope = access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,A);
    assertEquals(2,scope.size()); assertEquals(Set.of(B,PROP),ids(scope));
    assertTrue(access.current(AccessEntityTypeEnum.BUSINESS_DASHBOARD,A).tenantScope().isEmpty());
  }
  @Test void unrelatedTenantIsDeniedEvenWithRule() {
    grant(USER,ENTERPRISE,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> access.current(AccessEntityTypeEnum.BUSINESS_DASHBOARD,ENTERPRISE));
  }
  @Test void recursivelyRequiresEveryRegionalLevel() {
    String nested = "aaaaaaaaaaaaaaaaaaaaaaaa", nestedEnterprise = "bbbbbbbbbbbbbbbbbbbbbbbb", nestedUserId = "cccccccccccccccccccccccc";
    var nestedUser = user(nestedUserId,nested);
    when(mongo.find(any(Query.class),eq(UserEntity.class))).thenReturn(List.of(regionalUser,nestedUser));
    var root = ref(A,PartnerTypeEnum.ENTERPRISE,ENTERPRISE,null);
    when(tenants.propositionReferences()).thenReturn(List.of(root,
        ref(B,PartnerTypeEnum.REGIONAL_ENTERPRISE,REGIONAL_ENTERPRISE,null),
        ref(nested,PartnerTypeEnum.REGIONAL_ENTERPRISE,nestedEnterprise,null),ref(PROP,PartnerTypeEnum.BRAND,null,nested)));
    when(enterprises.dashboardChildEnterpriseIds(REGIONAL_ENTERPRISE)).thenReturn(List.of(nestedEnterprise));
    grant(USER,B,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    var middle = grant(REGIONAL_USER,nested,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    grant(nestedUserId,PROP,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    assertEquals(Set.of(B,nested,PROP),ids(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,A)));
    middle.setDeleted(true);
    assertEquals(Set.of(B),ids(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,A)));
  }
  @Test void deletedRegionalUserCannotProvideDownstreamGrant() {
    grant(USER,B,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    grant(REGIONAL_USER,PROP,AccessEntityTypeEnum.BUSINESS_DASHBOARD);
    regionalUser.setDeleted(true);
    assertEquals(Set.of(B),ids(access.scopedTenants(AccessEntityTypeEnum.BUSINESS_DASHBOARD,A)));
  }
  private Set<String> ids(List<DashboardActor> actors) { return actors.stream().map(a -> a.tenant().id()).collect(java.util.stream.Collectors.toSet()); }
  private UserEntity user(String id,String tenant) {
    var user = new UserEntity(); user.setId(new ObjectId(id));
    user.setPartyRelationships(List.of(PartyToPartyRelationship.builder().relationshipTypeCode("TENANT").toPartyId(new ObjectId(tenant)).build()));
    return user;
  }
  private PropositionTenantReference ref(String id,PartnerTypeEnum type,String enterprise,String parent) {
    return new PropositionTenantReference(id,id,enterprise,type,TenantTypeEnum.MASTER_TENANT,List.of(id),
        parent == null ? List.of() : List.of(new PartyRelationshipResponse(parent,"MT",parent)),false);
  }
  private Group grant(String user,String target,AccessEntityTypeEnum type) {
    var group = Group.builder().groupType(GroupType.ACCESS_CONTROL_GROUP).accessEntityTypes(List.of(type)).accessLevel(AccessLevelEnum.FULL_ACCESS)
        .partyRelationships(List.of(PartyToPartyRelationship.builder().relationshipTypeCode("TENANT").toPartyId(new ObjectId(target)).build())).build();
    group.setId(new ObjectId()); groups.put(group.getId(),group);
    var membership = memberships.computeIfAbsent(new ObjectId(user),id -> UserGroupEntity.builder().userIdentifier(id).groups(new ArrayList<>()).build());
    membership.getGroups().add(UserGroupEntity.EmbeddedRestrictionGroup.builder().tenantIdentifier(new ObjectId(target)).groupIdentifier(group.getId()).build());
    return group;
  }
}
