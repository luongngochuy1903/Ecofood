package com.ecofood.app.dashboard;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.ecofood.app.dashboard.controller.DashboardController;
import com.ecofood.app.dashboard.exception.DashboardApiErrors;
import com.ecofood.app.dashboard.security.DashboardAccess;
import com.ecofood.app.dashboard.service.DashboardServiceImpl;
import com.ecofood.core.client.CoreDashboardInvoiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class DashboardApiTest {
  @Test void invoiceCheckboxSelectionMergesOverlappingScopesBeforeOnePaginatedRead() {
    var access = mock(DashboardAccess.class); var invoices = mock(CoreDashboardInvoiceClient.class);
    var b = new com.ecofood.core.dto.DashboardActor(DashboardFixtures.USER,DashboardFixtures.tenant(DashboardFixtures.B,false),java.util.Set.of(DashboardFixtures.B),false,null);
    var c = new com.ecofood.core.dto.DashboardActor(DashboardFixtures.USER,DashboardFixtures.tenant(DashboardFixtures.OWNER,false),java.util.Set.of(DashboardFixtures.OWNER),false,java.util.Set.of("invoice-c"));
    when(access.scopedTenants(com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD,DashboardFixtures.A)).thenReturn(java.util.List.of(b,c));
    when(access.scopedTenants(com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD,DashboardFixtures.B)).thenReturn(java.util.List.of(b));
    new DashboardController(mock(DashboardServiceImpl.class),access,invoices).invoices(2,20,null,java.util.List.of(DashboardFixtures.A,DashboardFixtures.B,DashboardFixtures.A));
    verify(invoices).dashboard(org.mockito.ArgumentMatchers.argThat(scopes -> scopes.size() == 2 && scopes.containsKey(DashboardFixtures.B)
        && scopes.get(DashboardFixtures.B) == null && java.util.Set.of("invoice-c").equals(scopes.get(DashboardFixtures.OWNER))),eq(2),eq(20));
    verify(access,times(1)).scopedTenants(com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD,DashboardFixtures.A);
  }
  @Test void invoiceCheckboxSelectionDeniesMissingDarBeforeReadingAnyInvoices() {
    var access = mock(DashboardAccess.class); var invoices = mock(CoreDashboardInvoiceClient.class);
    when(access.scopedTenants(com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD,DashboardFixtures.A)).thenReturn(java.util.List.of(DashboardFixtures.actor(false)));
    var controller = new DashboardController(mock(DashboardServiceImpl.class),access,invoices);
    org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
        () -> controller.invoices(0,20,null,java.util.List.of(DashboardFixtures.A,DashboardFixtures.B)));
    verifyNoInteractions(invoices);
  }
  @Test void invoiceSelectionRejectsEmptyOrAmbiguousTenantFilters() {
    var access = mock(DashboardAccess.class); var invoices = mock(CoreDashboardInvoiceClient.class);
    var controller = new DashboardController(mock(DashboardServiceImpl.class),access,invoices);
    org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> controller.invoices(0,20,null,java.util.List.of()));
    org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,() -> controller.invoices(0,20,DashboardFixtures.A,java.util.List.of(DashboardFixtures.B)));
    verifyNoInteractions(access,invoices);
  }
  @Test void noInvoiceRuleDoesNotQueryInvoices() {
    var access = mock(DashboardAccess.class); var invoices = mock(CoreDashboardInvoiceClient.class);
    var actor = new com.ecofood.core.dto.DashboardActor(DashboardFixtures.USER,DashboardFixtures.tenant(DashboardFixtures.A,false),java.util.Set.of(),false,java.util.Set.of());
    when(access.scopedTenants(com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD,null)).thenReturn(java.util.List.of());
    var controller = new DashboardController(mock(DashboardServiceImpl.class),access,invoices);
    org.junit.jupiter.api.Assertions.assertTrue(controller.invoices(0,20,null).isEmpty());
    verifyNoInteractions(invoices);
  }
  @Test void limitedInvoiceIdsAreForwardedWithTenantAndPagination() {
    var access = mock(DashboardAccess.class); var invoices = mock(CoreDashboardInvoiceClient.class);
    var actor = new com.ecofood.core.dto.DashboardActor(DashboardFixtures.USER,DashboardFixtures.tenant(DashboardFixtures.A,false),java.util.Set.of(DashboardFixtures.A),false,java.util.Set.of("invoice"));
    when(access.scopedTenants(com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD,null)).thenReturn(java.util.List.of(actor));
    new DashboardController(mock(DashboardServiceImpl.class),access,invoices).invoices(1,20,null);
    verify(invoices).dashboard(java.util.Map.of(DashboardFixtures.A,java.util.Set.of("invoice")),1,20);
  }
  @Test void invoiceDetailWithoutRuleDoesNotReadInvoice() {
    var access = mock(DashboardAccess.class); var invoices = mock(CoreDashboardInvoiceClient.class);
    when(access.current(com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD,null)).thenReturn(
        new com.ecofood.core.dto.DashboardActor(DashboardFixtures.USER,DashboardFixtures.tenant(DashboardFixtures.A,false),java.util.Set.of(),false,java.util.Set.of()));
    var controller = new DashboardController(mock(DashboardServiceImpl.class),access,invoices);
    org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.invoiceDetail("invoice",null));
    verifyNoInteractions(invoices);
  }
  @Test void invoiceDetailCannotReadAnIdOutsideTheRule() {
    var access = mock(DashboardAccess.class); var invoices = mock(CoreDashboardInvoiceClient.class);
    when(access.current(com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD,null)).thenReturn(
        new com.ecofood.core.dto.DashboardActor(DashboardFixtures.USER,DashboardFixtures.tenant(DashboardFixtures.A,false),java.util.Set.of(DashboardFixtures.A),false,java.util.Set.of("allowed")));
    var controller = new DashboardController(mock(DashboardServiceImpl.class),access,invoices);
    org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.invoiceDetail("other",null));
    verifyNoInteractions(invoices);
    controller.invoiceDetail("allowed",null);
    verify(invoices).detail(DashboardFixtures.A,"allowed");
  }
  @Test void invoiceDetailUsesAuthorizedTenant() {
    var access = mock(DashboardAccess.class); var invoices = mock(CoreDashboardInvoiceClient.class);
    when(access.current(com.ecofood.core.enums.AccessEntityTypeEnum.INVOICE_DASHBOARD,DashboardFixtures.B)).thenReturn(
        new com.ecofood.core.dto.DashboardActor(DashboardFixtures.USER,DashboardFixtures.tenant(DashboardFixtures.B,false),java.util.Set.of(DashboardFixtures.B),false,null));
    new DashboardController(mock(DashboardServiceImpl.class),access,invoices).invoiceDetail("invoice",DashboardFixtures.B);
    verify(invoices).detail(DashboardFixtures.B,"invoice");
  }
  @Test void dashboardAccessReportsMissingCustomerRuleWithoutReadingData() throws Exception {
    var service = mock(DashboardServiceImpl.class); var access = mock(DashboardAccess.class); var invoices = mock(CoreDashboardInvoiceClient.class);
    var mvc = MockMvcBuilders.standaloneSetup(new DashboardController(service,access,invoices)).build();
    mvc.perform(get("/api/v1/dashboard/access").param("type","CUSTOMER_DASHBOARD"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
    verify(access).authorizedTenants(com.ecofood.core.enums.AccessEntityTypeEnum.CUSTOMER_DASHBOARD);
    verifyNoInteractions(service,invoices);
  }
  @Test void dashboardAccessReportsGrantedCustomerRule() throws Exception {
    var access = mock(DashboardAccess.class);
    when(access.authorizedTenants(com.ecofood.core.enums.AccessEntityTypeEnum.CUSTOMER_DASHBOARD)).thenReturn(java.util.List.of(DashboardFixtures.actor(false)));
    var mvc = MockMvcBuilders.standaloneSetup(new DashboardController(mock(DashboardServiceImpl.class),access,mock(CoreDashboardInvoiceClient.class))).build();
    mvc.perform(get("/api/v1/dashboard/access").param("type","CUSTOMER_DASHBOARD"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true));
  }
  @Test void dashboardAccessRejectsUnrelatedEntityTypes() throws Exception {
    var access = mock(DashboardAccess.class);
    var mvc = MockMvcBuilders.standaloneSetup(new DashboardController(mock(DashboardServiceImpl.class),access,mock(CoreDashboardInvoiceClient.class))).build();
    mvc.perform(get("/api/v1/dashboard/access").param("type","Product")).andExpect(status().isBadRequest());
    verifyNoInteractions(access);
  }
  @Test void summaryAcceptsMultipleCheckboxTenantIds() throws Exception {
    var service = mock(DashboardServiceImpl.class);
    var mvc = MockMvcBuilders.standaloneSetup(new DashboardController(service,mock(DashboardAccess.class),mock(CoreDashboardInvoiceClient.class))).build();
    mvc.perform(get("/api/v1/dashboard/summary").param("tenantIds",DashboardFixtures.A+","+DashboardFixtures.B+","+DashboardFixtures.A).param("currency","USD"))
        .andExpect(status().isOk());
    verify(service).summary(org.mockito.ArgumentMatchers.argThat(s -> s.tenantId() == null && s.tenantIds().equals(java.util.List.of(DashboardFixtures.A,DashboardFixtures.B))));
  }
  @Test void invalidFiltersAreRejected() throws Exception {
    var service = mock(DashboardServiceImpl.class);
    var mvc = MockMvcBuilders.standaloneSetup(new DashboardController(service,mock(DashboardAccess.class),mock(CoreDashboardInvoiceClient.class)))
        .setControllerAdvice(new DashboardApiErrors()).build();
    mvc.perform(get("/api/v1/dashboard/summary")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/dashboard/summary").param("currency","USD").param("period","INVALID")).andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }
}
