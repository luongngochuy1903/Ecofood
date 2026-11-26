package com.ecofood.app.userACL.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.ecofood.app.userACL.repository.*;
import com.ecofood.app.userACL.entity.UserEntity;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.service.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.entity.RoleLevel;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.*;
class DataAccessRuleUserScopeTest {
  @Test void pickerQueriesExactlySelectedTenantAndDirectParentWithoutPageLimit() {
    var users = mock(UserRepository.class); var tenants = mock(CoreTenantQueryClient.class); var acl = mock(ACLService.class);
    when(acl.getMaxLevel()).thenReturn(RoleLevel.SYSTEM);
    String child = new ObjectId().toHexString(), parent = new ObjectId().toHexString(), sibling = new ObjectId().toHexString();
    when(tenants.propositionReference(child)).thenReturn(new PropositionTenantReference(child,"Child",null,null,null,List.of(child,sibling),
        List.of(new PartyRelationshipResponse(parent,"MT","Parent")),false));
    var user = UserEntity.builder().firstName("Parent user").build(); user.setId(new ObjectId());
    when(users.findDirectory(anyList(),eq(false),isNull(),any())).thenReturn(new PageImpl<>(List.of(user)));
    var service = new DataAccessRuleServiceImpl(mock(GroupRepository.class),mock(GroupEntityRepository.class),mock(UserGroupEntityRepository.class),users,tenants,mock(SequenceGeneratorService.class),acl);
    var result = service.eligibleUsers(child);
    assertEquals(1,result.size());
    verify(users).findDirectory(eq(List.of(new ObjectId(child),new ObjectId(parent))),eq(false),isNull(),eq(Pageable.unpaged()));
  }
}
