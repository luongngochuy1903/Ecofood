package com.ecofood.app.catalog;

import static com.ecofood.app.catalog.CatalogFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.catalog.security.CatalogAccess;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.entity.Group;
import com.ecofood.app.userACL.entity.GroupEntity;
import com.ecofood.app.userACL.entity.PartyToPartyRelationship;
import com.ecofood.app.userACL.entity.UserCategory;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.app.userACL.entity.UserGroupEntity;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.enums.*;
import com.ecofood.core.enums.AccessEntityTypeEnum;
import com.ecofood.core.enums.AccessLevelEnum;
import com.ecofood.core.enums.GroupType;
import com.ecofood.core.enums.PartnerTypeEnum;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.UserAccessService.UserAccessClaims;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

class CatalogAccessTest {
  @Test void employeeRoleRequiresMatchingLiveTokenPermissionsMembershipAndPartnerIdentity() {
    var directory=mock(NotificationDirectory.class); var tenants=mock(CoreTenantQueryClient.class); var mongo=mock(MongoTemplate.class); var access=new CatalogAccess(directory,tenants,mongo);
    var user=new UserEntity(); user.setId(new ObjectId(USER)); user.setUserCategory(UserCategory.builder().userCategoryCode("PARTNER").build());
    when(directory.user(USER,A)).thenReturn(user); when(tenants.propositionReference(A)).thenReturn(tenant(A,PartnerTypeEnum.ECOSYSTEM_PARTNER));
    when(directory.claims(user,A)).thenReturn(new UserAccessClaims(List.of("TENANT_EMPLOYEE"),List.of(CatalogAccess.READ,CatalogAccess.MANAGE),List.of("TENANT")));
    try(var token=mockStatic(AuthenticationContext.class)) {
      token.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(USER)); token.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(A));
      token.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_EMPLOYEE")); token.when(AuthenticationContext::getPermissions).thenReturn(List.of(CatalogAccess.READ,CatalogAccess.MANAGE,CatalogAccess.PUBLISH));
      var actor=access.current(); assertTrue(actor.permissions().contains(CatalogAccess.MANAGE)); assertFalse(actor.permissions().contains(CatalogAccess.PUBLISH));
      token.when(AuthenticationContext::getPermissions).thenReturn(List.of()); assertThrows(ResponseStatusException.class,access::current);
      token.when(AuthenticationContext::getPermissions).thenReturn(List.of(CatalogAccess.READ)); user.setUserCategory(UserCategory.builder().userCategoryCode("CUSTOMER").build()); assertThrows(ResponseStatusException.class,access::current);
      user.setUserCategory(UserCategory.builder().userCategoryCode("PARTNER").build()); when(directory.user(USER,A)).thenReturn(null); assertThrows(ResponseStatusException.class,access::current);
    }
  }
  @Test void publisherAuthorityIsRecheckedFromLiveDataWithoutReusingAnEmployeeJwt() {
    var directory=mock(NotificationDirectory.class); var tenants=mock(CoreTenantQueryClient.class); var mongo=mock(MongoTemplate.class); var access=new CatalogAccess(directory,tenants,mongo);
    var user=new UserEntity(); user.setId(new ObjectId(USER)); user.setUserCategory(UserCategory.builder().userCategoryCode("PARTNER").build());
    when(directory.user(USER,A)).thenReturn(user); when(tenants.propositionReference(A)).thenReturn(tenant(A,PartnerTypeEnum.ECOSYSTEM_PARTNER));
    when(directory.claims(user,A)).thenReturn(new UserAccessClaims(List.of("TENANT_EMPLOYEE"),List.of(CatalogAccess.READ,CatalogAccess.PUBLISH),List.of()));
    assertEquals(USER,access.publisher(USER,A).userId());
    when(directory.claims(user,A)).thenReturn(new UserAccessClaims(List.of("TENANT_EMPLOYEE"),List.of(CatalogAccess.READ),List.of())); assertThrows(ResponseStatusException.class,()->access.publisher(USER,A));
  }
  @Test void limitedProductDataScopeIsNotBroadenedByEmployeeRoleOrPublishPermission() {
    var directory=mock(NotificationDirectory.class); var tenants=mock(CoreTenantQueryClient.class); var mongo=mock(MongoTemplate.class); var access=new CatalogAccess(directory,tenants,mongo);
    var user=new UserEntity(); user.setId(new ObjectId(USER)); user.setUserCategory(UserCategory.builder().userCategoryCode("PARTNER").build());
    when(directory.user(USER,A)).thenReturn(user); when(tenants.propositionReference(A)).thenReturn(tenant(A,PartnerTypeEnum.ECOSYSTEM_PARTNER));
    when(directory.claims(user,A)).thenReturn(new UserAccessClaims(List.of("TENANT_EMPLOYEE"),List.of(CatalogAccess.READ,CatalogAccess.PUBLISH),List.of()));
    var groupId=new ObjectId(OWNER); var membership=new UserGroupEntity(); membership.setGroups(List.of(UserGroupEntity.EmbeddedRestrictionGroup.builder().tenantIdentifier(new ObjectId(A)).groupIdentifier(groupId).build()));
    var group=new Group(); group.setId(groupId); group.setGroupType(GroupType.ACCESS_CONTROL_GROUP); group.setAccessEntityType(AccessEntityTypeEnum.Product); group.setAccessLevel(AccessLevelEnum.LIMITED_ACCESS);
    group.setPartyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(A)).relationshipTypeCode("TENANT").build()));
    var entities=new GroupEntity(); entities.setEntityIds(List.of(new ObjectId(PRODUCT)));
    when(mongo.findOne(any(Query.class),eq(UserGroupEntity.class))).thenReturn(membership); when(mongo.findById(groupId,Group.class)).thenReturn(group); when(mongo.find(any(Query.class),eq(GroupEntity.class))).thenReturn(List.of(entities));
    var actor=access.publisher(USER,A); assertEquals(Set.of(PRODUCT),actor.productIds()); assertDoesNotThrow(()->actor.product(PRODUCT)); assertThrows(ResponseStatusException.class,()->actor.product("other-product"));
  }
}
