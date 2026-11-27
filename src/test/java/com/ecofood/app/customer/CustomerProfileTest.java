package com.ecofood.app.customer;

import static com.ecofood.app.customer.CustomerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.customer.entity.CustomerUser;
import com.ecofood.app.customer.security.CustomerAccess;
import com.ecofood.app.customer.service.CustomerServiceImpl;
import com.ecofood.app.userACL.entity.UserCategory;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.CustomerDtos;
import com.ecofood.core.dto.PropositionTenantReference;
import com.ecofood.core.enums.*;
import com.ecofood.core.enums.PartnerTypeEnum;
import com.ecofood.core.enums.TenantTypeEnum;
import com.ecofood.core.security.AuthenticationContext;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.web.server.ResponseStatusException;

class CustomerProfileTest {
  @Test void customerProfileUsesVerifiedInternalSubjectAndCurrentTenantMembership() {
    var mongo = mock(MongoTemplate.class); var tenants = mock(CoreTenantQueryClient.class); var user = user(A);
    when(mongo.findById(new ObjectId(A), CustomerUser.class)).thenReturn(user);
    when(tenants.propositionReference(TENANT)).thenReturn(new PropositionTenantReference(TENANT, "Test", null, PartnerTypeEnum.BRAND, TenantTypeEnum.STANDALONE, List.of(TENANT), List.of(), false));
    try (var auth = mockStatic(AuthenticationContext.class)) {
      auth.when(AuthenticationContext::getUserId).thenReturn(new ObjectId(A)); auth.when(AuthenticationContext::getTenantId).thenReturn(new ObjectId(TENANT));
      var access = new CustomerAccess(mongo, tenants); var profile = new CustomerServiceImpl(access, mongo, mock(com.ecofood.core.client.CoreTenantQueryClient.class)).profile(); assertEquals(A, profile.id()); assertEquals(user.getEmail(), profile.email());
      user.setUserCategory(UserCategory.builder().userCategoryCode("PARTNER").build()); assertThrows(ResponseStatusException.class, access::current);
      user.setUserCategory(UserCategory.builder().userCategoryCode("CUSTOMER").build());
      String originalEmail = user.getEmail(); user.setEmail("changed@example.org"); assertThrows(ResponseStatusException.class, access::current); user.setEmail(originalEmail);
      user.setCustomerEmailVerifiedAt(null); assertThrows(ResponseStatusException.class, access::current);
    }
  }
  @Test void profileUpdateChangesOnlyPermittedFieldsWithRevisionAndTenantPredicate() {
    var access = mock(CustomerAccess.class); when(access.current()).thenReturn(actor(A)); var mongo = mock(MongoTemplate.class); var updated = user(A); updated.setCustomerProfileRevision(1L);
    when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), eq(CustomerUser.class))).thenReturn(updated);
    var service = new CustomerServiceImpl(access, mongo, mock(com.ecofood.core.client.CoreTenantQueryClient.class)); assertEquals(1, service.update(new CustomerDtos.UpdateProfile("New", "Name", 0L)).revision());
    var query = org.mockito.ArgumentCaptor.forClass(Query.class); var update = org.mockito.ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).findAndModify(query.capture(), update.capture(), any(FindAndModifyOptions.class), eq(CustomerUser.class));
    assertTrue(query.getValue().getQueryObject().toJson().contains("customerProfileRevision")); assertTrue(query.getValue().getQueryObject().toJson().contains(TENANT));
    var fields = update.getValue().getUpdateObject().get("$set", org.bson.Document.class);
    assertEquals(Set.of("firstName", "lastName", "updatedBy", "updatedAt"), fields.keySet()); assertFalse(fields.containsKey("email"));
  }
  @Test void staleProfileAndInvalidPaginationDoNotOverwriteOtherRecords() {
    var access = mock(CustomerAccess.class); when(access.current()).thenReturn(actor(A)); var service = new CustomerServiceImpl(access, mock(MongoTemplate.class), mock(com.ecofood.core.client.CoreTenantQueryClient.class));
    var updateProfile1 = new CustomerDtos.UpdateProfile("First", "Last", 3L);
    assertThrows(ResponseStatusException.class, () -> service.update(updateProfile1));
    assertThrows(ResponseStatusException.class, () -> service.invoices(-1, 20));
  }
}
