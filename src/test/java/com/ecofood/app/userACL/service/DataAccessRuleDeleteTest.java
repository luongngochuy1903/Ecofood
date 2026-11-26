package com.ecofood.app.userACL.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.entity.Group;
import com.ecofood.app.userACL.repository.*;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.entity.RoleLevel;
import com.ecofood.core.enums.*;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.*;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class DataAccessRuleDeleteTest {
  final GroupRepository groups = mock(GroupRepository.class);
  final GroupEntityRepository entities = mock(GroupEntityRepository.class);
  final UserGroupEntityRepository users = mock(UserGroupEntityRepository.class);
  final ACLService acl = mock(ACLService.class);
  final CoreTenantQueryClient tenants = mock(CoreTenantQueryClient.class);
  final DataAccessRuleServiceImpl service = new DataAccessRuleServiceImpl(groups,entities,users,mock(UserRepository.class),tenants,mock(SequenceGeneratorService.class),acl);
  final ObjectId owner = new ObjectId(), tenant = new ObjectId(), id = new ObjectId();
  Group rule;
  MockedStatic<AuthenticationContext> auth;
  @BeforeEach void setup() {
    auth = mockStatic(AuthenticationContext.class);
    auth.when(AuthenticationContext::getUserId).thenReturn(owner);
    auth.when(AuthenticationContext::getTenantId).thenReturn(tenant);
    when(acl.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    rule = Group.builder().groupType(GroupType.ACCESS_CONTROL_GROUP).partyRelationships(List.of(PartyToPartyRelationship.builder()
        .relationshipTypeCode("TENANT").toPartyId(tenant).build())).build();
    rule.setId(id); rule.setCreatedBy(owner);
    when(groups.findById(id)).thenReturn(Optional.of(rule));
  }
  @AfterEach void close() { auth.close(); }
  @Test void deletesRuleAndRevokesOnlyItsOwnMembershipsAndEntities() {
    ObjectId other = new ObjectId();
    var membership = UserGroupEntity.builder().userIdentifier(owner).groups(new ArrayList<>(List.of(
        UserGroupEntity.EmbeddedRestrictionGroup.builder().groupIdentifier(id).build(),
        UserGroupEntity.EmbeddedRestrictionGroup.builder().groupIdentifier(other).build()))).build();
    when(users.findAllByGroups_GroupIdentifier(id)).thenReturn(List.of(membership));
    var child = GroupEntity.builder().groupIdentifier(id).build();
    when(entities.findAllByGroupIdentifier(id)).thenReturn(List.of(child));
    service.delete(id.toHexString());
    assertTrue(rule.getDeleted());
    assertEquals(1,membership.getGroups().size());
    assertEquals(other,membership.getGroups().get(0).getGroupIdentifier());
    var order = inOrder(groups,users,entities);
    order.verify(groups).save(rule);
    order.verify(users).findAllByGroups_GroupIdentifier(id);
    order.verify(users).save(membership);
    order.verify(entities).findAllByGroupIdentifier(id);
    order.verify(entities).deleteAll(List.of(child));
  }
  @Test void nonCreatorCannotDelete() {
    rule.setCreatedBy(new ObjectId());
    assertThrows(ForbiddenException.class,()->service.delete(id.toHexString()));
    verify(groups,never()).save(any()); verifyNoInteractions(users,entities);
  }
  @Test void deniedRoleCannotDeleteEvenWhenCreator() {
    when(acl.getMaxLevel()).thenReturn(RoleLevel.CUSTOMER);
    assertThrows(ForbiddenException.class,()->service.delete(id.toHexString()));
    verify(groups,never()).save(any()); verifyNoInteractions(users,entities);
  }
  @Test void creatorCannotDeleteOutsideCurrentTenantScope() {
    when(acl.getMaxLevel()).thenReturn(RoleLevel.TENANT);
    auth.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId());
    assertThrows(ForbiddenException.class,()->service.delete(id.toHexString()));
    verify(groups,never()).save(any()); verifyNoInteractions(users,entities);
  }
  @Test void roleGroupsCannotBeDeletedViaRulesEndpoint() {
    rule.setGroupType(GroupType.ROLE_GROUP);
    assertThrows(IllegalArgumentException.class,()->service.delete(id.toHexString()));
    verify(groups,never()).save(any());
  }
  @Test void deletedRuleCannotBeReadOrUpdatedButCleanupCanBeRetried() {
    rule.setDeleted(true);
    assertThrows(IllegalArgumentException.class,()->service.getById(id.toHexString()));
    assertThrows(IllegalArgumentException.class,()->service.update(id.toHexString(),new com.ecofood.core.dto.UpdateDataAccessRuleRequest()));
    service.delete(id.toHexString());
    verify(groups).save(rule);
  }
  @Test void directoryExcludesDeletedRulesBeforeCountingAndPaging() {
    var mongo = mock(MongoTemplate.class);
    when(mongo.find(any(Query.class),eq(Group.class))).thenReturn(List.of());
    new GroupRepositoryImpl(mongo).findDirectory(GroupType.ACCESS_CONTROL_GROUP,List.of(),true,null,Pageable.unpaged());
    verify(mongo).count(argThat((Query q) -> Boolean.TRUE.equals(((org.bson.Document)q.getQueryObject().get("deleted")).get("$ne"))),eq(Group.class));
    verify(mongo).find(argThat((Query q) -> q.getQueryObject().containsKey("deleted")),eq(Group.class));
  }
}
