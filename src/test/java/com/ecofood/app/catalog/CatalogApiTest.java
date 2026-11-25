package com.ecofood.app.catalog;

import static com.ecofood.app.catalog.CatalogFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ecofood.app.catalog.controller.CatalogController;
import com.ecofood.app.catalog.controller.CatalogExternalController;
import com.ecofood.app.catalog.exception.CatalogApiErrors;
import com.ecofood.app.catalog.security.CatalogExternalAccess;
import com.ecofood.app.catalog.service.CatalogPublicationServiceImpl;
import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.core.dto.CatalogDtos;
import com.ecofood.core.dto.CatalogOrchestrator;
import com.ecofood.core.enums.CatalogTypeEnum;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CatalogApiTest {
  @Test void internalDetailListNeverExposeCredentialOrHashAndExternalDtoContainsNoInternalFinance() throws Exception {
    var f=new CatalogFixtures(); var p=f.published(CatalogTypeEnum.PRICE,1); var key=f.credential(p.id(),B);
    var mvc=MockMvcBuilders.standaloneSetup(new CatalogController(f.publications)).setControllerAdvice(new CatalogApiErrors(),new GlobalExceptionHandler()).build();
    var response=mvc.perform(get("/api/v1/catalog-publications/"+p.id()).header("X-Tenant-Id",C)).andExpect(status().isOk())
        .andExpect(jsonPath("$.grants[0].credentialHash").doesNotExist()).andExpect(jsonPath("$.grants[0].credential").doesNotExist()).andReturn().getResponse().getContentAsString();
    assertFalse(response.contains(key.credential())); assertFalse(response.contains("credentialHash"));
    var external=f.external.read(f.external.authenticate(key.credential()),p.id(),1,null,null,0,20);
    var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(external);
    for(String privateField:List.of("costing","ProfitShare","ingredientCost","submittedBy","recipe","invoiceHolder","credentialHash","sourceProcess","processRevision")) assertFalse(json.contains(privateField),privateField);
    assertTrue(json.contains("11.00")); assertTrue(json.contains("USD"));
  }
  @Test void unsupportedVoucherComboAndInvalidCustomFieldsCannotBeCreated() throws Exception {
    var service=mock(CatalogPublicationServiceImpl.class); var mvc=MockMvcBuilders.standaloneSetup(new CatalogController(service)).setControllerAdvice(new CatalogApiErrors(),new GlobalExceptionHandler()).build();
    mvc.perform(get("/api/v1/catalog-publications/options?type=VOUCHER")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/catalog-publications/options?type=COMBO")).andExpect(status().isBadRequest()); verifyNoInteractions(service);
    var f=new CatalogFixtures(); var draft=f.draft(CatalogTypeEnum.PRODUCT,1);
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->f.publications.save(null,new CatalogDtos.Draft(draft.apiKey(),draft.name(),draft.catalogType(),1,draft.selections(),draft.partnerIds(),draft.policy(),List.of(new CatalogOrchestrator.CustomField("costing.password","expose")),null)));
  }
  @Test void externalControllerDoesNotAcceptAnInternalPrincipalEvenWithForgedAuthority() {
    var service=mock(CatalogExternalAccess.class); var controller=new CatalogExternalController(service);
    var internal=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("internal-user",null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("CATALOG_READ")));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.session(internal)); verifyNoInteractions(service);
  }
}
