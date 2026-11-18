package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ecofood.app.complaint.ComplaintCoreFixture.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintDirectorySources.*;
import com.ecofood.app.complaint.ComplaintRoutingCatalog.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class MongoComplaintRoutingDirectoryTest {
  final Tenants tenants = mock(Tenants.class);
  final Employees employees = mock(Employees.class);
  final MongoComplaintRoutingDirectory directory = new MongoComplaintRoutingDirectory(new ComplaintMongoStore(mock(MongoTemplate.class)), tenants, employees);
  @Test void deletedOrInactiveTenantCannotRetainEmployeePermission() {
    when(tenants.find(SELLER)).thenReturn(Optional.of(new TenantInfo(SELLER, TenantStructure.STANDALONE, null, false, false)));
    when(employees.resolve(HANDLER, SELLER)).thenReturn(new EmployeeFacts(true, Set.of(Permission.COMPLAINT_CREATE)));
    assertFalse(directory.isActiveEmployee(HANDLER, SELLER)); assertFalse(directory.hasPermission(HANDLER, SELLER, Permission.COMPLAINT_CREATE));
    verifyNoInteractions(employees);
  }
  @Test void snapshotCachesLiveIamWithinRequestAndRechecksAfterRequest() {
    when(tenants.find(SELLER)).thenReturn(Optional.of(new TenantInfo(SELLER, TenantStructure.STANDALONE, null, false, true)));
    when(employees.resolve(HANDLER, SELLER)).thenReturn(new EmployeeFacts(true, Set.of(Permission.COMPLAINT_READ)));
    directory.inSnapshot(() -> { assertTrue(directory.isActiveEmployee(HANDLER, SELLER)); assertTrue(directory.hasPermission(HANDLER, SELLER, Permission.COMPLAINT_READ)); return null; });
    verify(employees, times(1)).resolve(HANDLER, SELLER);
    when(employees.resolve(HANDLER, SELLER)).thenReturn(new EmployeeFacts(false, Set.of())); assertFalse(directory.isActiveEmployee(HANDLER, SELLER));
  }
  @Test void scopeRequiresExplicitComplaintGrantAndNeverTreatsPortalOrAnotherSellerAsInvoiceSource() {
    var f = new ComplaintCoreFixture(); var row = f.row(); row.setInvoiceId(INVOICE); row.setInvoiceTenantId(SELLER); row.setDistributionPartnerId("SHOP-OPAQUE");
    var grant = new ScopeGrant(HANDLER, "review", Set.of(Kind.INVOICE), Set.of(SELLER), false, false, Set.of(INVOICE));
    var config = new Configuration(1, PLATFORM_HANDLER, List.of(), List.of(), List.of(), List.of(), List.of(grant));
    directory.withConfiguration(config, () -> {
      assertTrue(directory.inDataScope(f.handler(), row.asCase(), Permission.COMPLAINT_READ));
      assertFalse(directory.inDataScope(f.actor(HANDLER, PORTAL), row.asCase(), Permission.COMPLAINT_READ));
      row.setInvoiceId(CASE); assertFalse(directory.inDataScope(f.handler(), row.asCase(), Permission.COMPLAINT_READ));
      row.setInvoiceId(INVOICE); row.setInvoiceTenantId(PORTAL); assertFalse(directory.inDataScope(f.handler(), row.asCase(), Permission.COMPLAINT_READ));
      return null;
    });
  }
}
