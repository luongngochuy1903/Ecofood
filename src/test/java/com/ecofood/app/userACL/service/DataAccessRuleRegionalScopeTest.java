package com.ecofood.app.userACL.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.entity.Group;
import com.ecofood.app.userACL.repository.*;
import com.ecofood.core.client.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.entity.RoleLevel;
import com.ecofood.core.enums.*;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.*;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.data.domain.*;

class DataAccessRuleRegionalScopeTest {
  final String root = new ObjectId().toHexString(), regional = new ObjectId().toHexString(), brand = new ObjectId().toHexString(),
      grandchild = new ObjectId().toHexString(), other = new ObjectId().toHexString(), enterprise = new ObjectId().toHexString(),
      regionalEnterprise = new ObjectId().toHexString();
  final CoreTenantQueryClient tenants = mock(CoreTenantQueryClient.class);
  final CoreEnterpriseQueryClient enterprises = mock(CoreEnterpriseQueryClient.class);
  final ACLService acl = mock(ACLService.class);
  final UserRepository users = mock(UserRepository.class);
  final GroupRepository groups = mock(GroupRepository.class);
  final UserGroupEntityRepository memberships = mock(UserGroupEntityRepository.class);
  final SequenceGeneratorService sequence = mock(SequenceGeneratorService.class);
  final DataAccessRuleServiceImpl service = new DataAccessRuleServiceImpl(groups,mock(GroupEntityRepository.class),memberships,
      users,tenants,sequence,acl,new DashboardTenantHierarchy(tenants,enterprises));
  MockedStatic<AuthenticationContext> auth;

  @BeforeEach void setup() {
    auth = mockStatic(AuthenticationContext.class);
    auth.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(root));
    when(acl.getMaxLevel()).thenReturn(RoleLevel.ENTERPRISE);
    var parent = ref(root,"Enterprise",PartnerTypeEnum.ENTERPRISE,enterprise,null);
    var child = ref(regional,"Regional",PartnerTypeEnum.REGIONAL_ENTERPRISE,regionalEnterprise,null);
    var directBrand = ref(brand,"Brand",PartnerTypeEnum.BRAND,null,root);
    var regionalBrand = ref(grandchild,"Regional brand",PartnerTypeEnum.BRAND,null,regional);
    var outside = ref(other,"Other",PartnerTypeEnum.REGIONAL_ENTERPRISE,new ObjectId().toHexString(),null);
    when(tenants.propositionReference(root)).thenReturn(parent);
    when(tenants.propositionReference(regional)).thenReturn(child);
    when(tenants.propositionReference(other)).thenReturn(outside);
    when(tenants.propositionReference(grandchild)).thenReturn(regionalBrand);
    when(tenants.propositionReferences()).thenReturn(List.of(parent,child,directBrand,regionalBrand,outside));
    when(tenants.getScopedTenantIds(root)).thenReturn(List.of(root,brand)); // Generic directory remains MT-only.
    when(enterprises.dashboardChildEnterpriseIds(enterprise)).thenReturn(List.of(regionalEnterprise));
  }
  @AfterEach void close() { auth.close(); }

  @Test void businessEntityPickerIncludesDirectRegionalWithoutRequiringDarAndExcludesGrandchildren() {
    assertEquals(Set.of(root,regional,brand),service.eligibleTenants().stream().map(DashboardDtos.Option::id)
        .collect(java.util.stream.Collectors.toSet()));
    verifyNoInteractions(groups,memberships,users);
  }
  @Test void regionalUserPickerIncludesEnterpriseOwnerWithoutMtRelationship() {
    var user = UserEntity.builder().firstName("Enterprise admin").build(); user.setId(new ObjectId());
    when(users.findDirectory(anyList(),eq(false),isNull(),any())).thenReturn(new PageImpl<>(List.of(user)));
    assertEquals(1,service.eligibleUsers(regional).size());
    verify(users).findDirectory(eq(List.of(new ObjectId(regional),new ObjectId(root))),eq(false),isNull(),eq(Pageable.unpaged()));
  }
  @Test void createsRegionalRuleForParentEnterpriseUserAndStoresRegionalMembershipScope() {
    var user = UserEntity.builder().firstName("Enterprise admin").build(); user.setId(new ObjectId());
    when(users.findDirectory(anyList(),eq(false),isNull(),any())).thenReturn(new PageImpl<>(List.of(user)));
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    when(tenants.getById(regional)).thenReturn(TenantResponse.builder().id(regional).tenantName("Regional").build());
    when(groups.save(any(Group.class))).thenAnswer(invocation -> { Group group = invocation.getArgument(0); group.setId(new ObjectId()); return group; });
    var request = new CreateDataAccessRuleRequest(); request.setBusinessEntityId(regional); request.setRuleName("Regional access");
    request.setDescription("Regional dashboards"); request.setAccessEntityTypes(List.of(AccessEntityTypeEnum.BUSINESS_DASHBOARD));
    request.setAccessLevel(AccessLevelEnum.FULL_ACCESS); request.setUserIds(List.of(user.getIdAsString()));
    assertEquals(regional,service.create(request).getBusinessEntityId());
    verify(memberships).save(argThat(membership -> user.getId().equals(membership.getUserIdentifier())
        && membership.getGroups().size() == 1 && new ObjectId(regional).equals(membership.getGroups().get(0).getTenantIdentifier())));
  }
  @Test void regionalRulesAreIncludedInDarDirectoryScope() {
    when(groups.findDirectory(any(),anyList(),eq(false),isNull(),any())).thenReturn(Page.empty());
    service.listDirectory(0,20,null,null);
    verify(groups).findDirectory(eq(GroupType.ACCESS_CONTROL_GROUP),argThat(ids -> new HashSet<>(ids)
        .equals(Set.of(new ObjectId(root),new ObjectId(brand),new ObjectId(regional)))),eq(false),isNull(),any());
  }
  @Test void cannotAssignRulesToUnrelatedRegionalOrGrandchild() {
    assertThrows(ForbiddenException.class,() -> service.eligibleUsers(other));
    assertThrows(ForbiddenException.class,() -> service.eligibleUsers(grandchild));
    verifyNoInteractions(users,groups,memberships);
  }
  @Test void tenantRoleCannotManageRegionalChildren() {
    when(acl.getMaxLevel()).thenReturn(RoleLevel.TENANT);
    assertEquals(List.of(root),service.eligibleTenants().stream().map(DashboardDtos.Option::id).toList());
    assertThrows(ForbiddenException.class,() -> service.eligibleUsers(regional));
  }
  @Test void unsupportedRoleCannotReadBusinessEntityPicker() {
    when(acl.getMaxLevel()).thenReturn(RoleLevel.CUSTOMER);
    assertThrows(ForbiddenException.class,service::eligibleTenants);
  }
  private PropositionTenantReference ref(String id,String name,PartnerTypeEnum type,String enterprise,String parent) {
    return new PropositionTenantReference(id,name,enterprise,type,TenantTypeEnum.MASTER_TENANT,List.of(id),
        parent == null ? List.of() : List.of(new PartyRelationshipResponse(parent,"MT",parent)),false);
  }
}
