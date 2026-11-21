package com.ecofood.app.complaint;

import static com.ecofood.app.complaint.ComplaintServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.notification.NotificationDirectory;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.PropositionTenantReference;
import com.ecofood.core.enums.*;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.UserAccessService.UserAccessClaims;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

class ComplaintAccessTest {
  static PropositionTenantReference tenant(String id,PartnerTypeEnum type) { return new PropositionTenantReference(id,"Tenant",null,type,TenantTypeEnum.STANDALONE,List.of(),List.of(),false); }
  @Test void tenantAdminRequiresLiveRoleTokenPermissionMembershipAndCannotAcquireEcoAccess() {
    var directory=mock(NotificationDirectory.class); var tenants=mock(CoreTenantQueryClient.class); var mongo=mock(MongoTemplate.class);
    var access=new ComplaintAccess(directory,tenants,mongo); var user=new UserEntity(); user.setId(new ObjectId(ADMIN));
    when(directory.user(ADMIN,TENANT)).thenReturn(user); when(tenants.propositionReference(TENANT)).thenReturn(tenant(TENANT,PartnerTypeEnum.BRAND));
    when(directory.claims(user,TENANT)).thenReturn(new UserAccessClaims(List.of("TENANT_ADMIN"),List.of(ComplaintAccess.READ,ComplaintAccess.MANAGE),List.of()));
    try(var token=mockStatic(AuthenticationContext.class)) {
      token.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(ADMIN)); token.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(TENANT));
      token.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_ADMIN")); token.when(AuthenticationContext::getPermissions).thenReturn(List.of(ComplaintAccess.READ));
      var actor=access.current(); assertEquals(List.of(Complaint.Type.TENANT),actor.types()); assertFalse(actor.manage()); assertFalse(actor.assign());
      token.when(AuthenticationContext::getPermissions).thenReturn(List.of()); assertThrows(ResponseStatusException.class,access::current);
      token.when(AuthenticationContext::getPermissions).thenReturn(List.of(ComplaintAccess.READ)); token.when(AuthenticationContext::getRoles).thenReturn(List.of("ECO_ADMIN")); assertThrows(ResponseStatusException.class,access::current);
      token.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_ADMIN")); when(directory.user(ADMIN,TENANT)).thenReturn(null); assertThrows(ResponseStatusException.class,access::current);
    }
  }
  @Test void ecoRoleOnlyWorksInRealEcoTenantAndNoGlobalAdminIsInferred() {
    var directory=mock(NotificationDirectory.class); var tenants=mock(CoreTenantQueryClient.class); var mongo=mock(MongoTemplate.class);
    var access=new ComplaintAccess(directory,tenants,mongo); var user=new UserEntity(); user.setId(new ObjectId(ADMIN));
    when(directory.user(ADMIN,ECO)).thenReturn(user); when(directory.claims(user,ECO)).thenReturn(new UserAccessClaims(List.of("ECO_ADMIN"),List.of(ComplaintAccess.READ,ComplaintAccess.MANAGE),List.of()));
    when(tenants.propositionReference(ECO)).thenReturn(tenant(ECO,PartnerTypeEnum.ECO));
    try(var token=mockStatic(AuthenticationContext.class)) {
      token.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(ADMIN)); token.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(ECO));
      token.when(AuthenticationContext::getRoles).thenReturn(List.of("ECO_ADMIN")); token.when(AuthenticationContext::getPermissions).thenReturn(List.of(ComplaintAccess.READ,ComplaintAccess.MANAGE));
      assertEquals(List.of(Complaint.Type.ECOSYSTEM),access.current().types());
      when(tenants.propositionReference(ECO)).thenReturn(tenant(ECO,PartnerTypeEnum.ECOSYSTEM_PARTNER)); assertThrows(ResponseStatusException.class,access::current);
    }
  }
  @Test void ecosystemRouteIsUniqueExistingEcoTenantNotCustomerInput() {
    var tenants=mock(CoreTenantQueryClient.class); var access=new ComplaintAccess(mock(NotificationDirectory.class),tenants,mock(MongoTemplate.class));
    when(tenants.propositionReferences()).thenReturn(List.of(tenant(TENANT,PartnerTypeEnum.BRAND),tenant(ECO,PartnerTypeEnum.ECO))); assertEquals(ECO,access.ecosystemTenant());
    when(tenants.propositionReferences()).thenReturn(List.of()); assertThrows(ResponseStatusException.class,access::ecosystemTenant);
    when(tenants.propositionReferences()).thenReturn(List.of(tenant(TENANT,PartnerTypeEnum.ECO),tenant(ECO,PartnerTypeEnum.ECO))); assertThrows(ResponseStatusException.class,access::ecosystemTenant);
  }
  @Test void eligibleAssigneeMustHaveLiveRoleManagePermissionAndExactTenant() {
    var directory=mock(NotificationDirectory.class); var access=new ComplaintAccess(directory,mock(CoreTenantQueryClient.class),mock(MongoTemplate.class)); var user=new UserEntity(); user.setId(new ObjectId(ADMIN));
    when(directory.user(ADMIN,TENANT)).thenReturn(user); when(directory.claims(user,TENANT)).thenReturn(new UserAccessClaims(List.of("TENANT_ADMIN"),List.of(ComplaintAccess.READ,ComplaintAccess.MANAGE),List.of()));
    assertTrue(access.eligible(ADMIN,TENANT,Complaint.Type.TENANT)); assertFalse(access.eligible(ADMIN,OTHER,Complaint.Type.TENANT));
    when(directory.claims(user,TENANT)).thenReturn(new UserAccessClaims(List.of("TENANT_ADMIN"),List.of(ComplaintAccess.READ),List.of())); assertFalse(access.eligible(ADMIN,TENANT,Complaint.Type.TENANT));
  }
  @Test void limitedCustomerOrInvoiceDataAccessCannotBeBypassedByAdminRole() {
    var directory=mock(NotificationDirectory.class); var tenants=mock(CoreTenantQueryClient.class); var mongo=mock(MongoTemplate.class);
    var access=new ComplaintAccess(directory,tenants,mongo); var user=new UserEntity(); user.setId(new ObjectId(ADMIN));
    when(directory.user(ADMIN,TENANT)).thenReturn(user); when(directory.claims(user,TENANT)).thenReturn(new UserAccessClaims(List.of("TENANT_ADMIN"),List.of(ComplaintAccess.READ,ComplaintAccess.MANAGE),List.of()));
    when(tenants.propositionReference(TENANT)).thenReturn(tenant(TENANT,PartnerTypeEnum.BRAND));
    var groupId=new ObjectId(OTHER); var membership=new UserGroupEntity(); membership.setGroups(List.of(UserGroupEntity.EmbeddedRestrictionGroup.builder().tenantIdentifier(new ObjectId(TENANT)).groupIdentifier(groupId).build()));
    var group=new Group(); group.setId(groupId); group.setGroupType(GroupType.ACCESS_CONTROL_GROUP); group.setAccessEntityType(AccessEntityTypeEnum.Dashboard_Customer); group.setAccessLevel(AccessLevelEnum.LIMITED_ACCESS);
    group.setPartyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(TENANT)).relationshipTypeCode("TENANT").build()));
    when(mongo.findOne(any(Query.class),eq(UserGroupEntity.class))).thenReturn(membership); when(mongo.findById(groupId,Group.class)).thenReturn(group);
    try(var token=mockStatic(AuthenticationContext.class)) {
      token.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(ADMIN)); token.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(TENANT));
      token.when(AuthenticationContext::getRoles).thenReturn(List.of("TENANT_ADMIN")); token.when(AuthenticationContext::getPermissions).thenReturn(List.of(ComplaintAccess.READ,ComplaintAccess.MANAGE));
      assertThrows(ResponseStatusException.class,access::current); assertFalse(access.eligible(ADMIN,TENANT,Complaint.Type.TENANT));
      group.setAccessLevel(AccessLevelEnum.FULL_ACCESS); assertDoesNotThrow(access::current); assertTrue(access.eligible(ADMIN,TENANT,Complaint.Type.TENANT));
    }
  }
}
