package com.ecofood.app.catalog;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ecofood.app.auth.security.*;
import com.ecofood.app.auth.security.InternalJwtAuthenticationEntryPoint;
import com.ecofood.app.auth.security.InternalJwtRequestFilter;
import com.ecofood.app.auth.service.InternalTokenService;
import com.ecofood.app.catalog.controller.CatalogController;
import com.ecofood.app.catalog.controller.CatalogExternalController;
import com.ecofood.app.catalog.exception.CatalogApiErrors;
import com.ecofood.app.catalog.security.CatalogExternalAccess;
import com.ecofood.app.catalog.security.CatalogSecurityConfig;
import com.ecofood.app.catalog.service.CatalogPublicationServiceImpl;
import com.ecofood.app.config.*;
import com.ecofood.app.config.GlobalExceptionHandler;
import com.ecofood.app.config.SecurityConfig;
import com.ecofood.core.dto.CatalogDtos;
import io.jsonwebtoken.Claims;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real Spring Security chains + HTTP tests, with only domain dependencies mocked; no Boot/Mongo/Keycloak context. */
class CatalogHttpSecurityTest {
  AnnotationConfigWebApplicationContext context; MockMvc mvc;
  @Configuration @EnableWebSecurity @EnableWebMvc @Import({CatalogSecurityConfig.class,SecurityConfig.class})
  static class Config {
    @Bean CatalogExternalAccess external() {
      var service=mock(CatalogExternalAccess.class); when(service.authenticate(any())).thenThrow(CatalogExternalAccess.unauthorized());
      var principal=new CatalogDtos.ExternalPrincipal("publication","partner","grant"); doReturn(principal).when(service).authenticate("Catalog test-only-credential");
      when(service.session(principal)).thenReturn(new CatalogDtos.Session("publication","partner","food",1,Instant.parse("2030-01-01T00:00:00Z"))); return service;
    }
    @Bean CatalogPublicationServiceImpl publications() {
      var service=mock(CatalogPublicationServiceImpl.class); when(service.capabilities()).thenReturn(new CatalogDtos.Capabilities("tenant",true,true,true,true)); return service;
    }
    @Bean InternalJwtRequestFilter internalFilter() {
      var tokens=mock(InternalTokenService.class); var claims=mock(Claims.class);
      when(claims.getId()).thenReturn("jti"); when(claims.getSubject()).thenReturn("employee"); when(claims.get("permissions",List.class)).thenReturn(List.of("CATALOG_API_READ"));
      when(tokens.verifyToken("internal-good")).thenReturn(claims); var filter=new InternalJwtRequestFilter(tokens);
      ReflectionTestUtils.setField(filter,"expectedIssuer","app"); ReflectionTestUtils.setField(filter,"expectedAudience","app-api"); ReflectionTestUtils.setField(filter,"jwtSecret","unit-test-internal-secret-long-enough-for-jwt"); return filter;
    }
    @Bean InternalJwtAuthenticationEntryPoint entryPoint() { return new InternalJwtAuthenticationEntryPoint(); }
    @Bean CatalogExternalController externalController(CatalogExternalAccess access) { return new CatalogExternalController(access); }
    @Bean CatalogController controller(CatalogPublicationServiceImpl publications) { return new CatalogController(publications); }
    @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
    @Bean CatalogApiErrors catalogErrors() { return new CatalogApiErrors(); }
  }
  @BeforeEach void setup() {
    context=new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
    mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean(FilterChainProxy.class)).build();
  }
  @AfterEach void close() { context.close(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
  @Test void externalApiRequiresItsOwnAuthenticationAndNeverAcceptsInternalBearer() throws Exception {
    mvc.perform(get("/api/external/v1/catalog/session")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/external/v1/catalog/session").header("Authorization","Bearer internal-good")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/external/v1/catalog/session").header("Authorization","Catalog test-only-credential")).andExpect(status().isOk()).andExpect(jsonPath("$.partnerId").value("partner"));
  }
  @Test void externalCredentialCannotAuthorizeInternalManagementAndInternalJwtStillWorks() throws Exception {
    mvc.perform(get("/api/v1/catalog-publications/capabilities").header("Authorization","Catalog test-only-credential")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/catalog-publications/capabilities").header("Authorization","Bearer internal-good")).andExpect(status().isOk()).andExpect(jsonPath("$.read").value(true));
  }
  @Test void externalCredentialTransportRequiresHttpsAwayFromLoopback() throws Exception {
    mvc.perform(get("/api/external/v1/catalog/session").with(request->{request.setRemoteAddr("192.0.2.10");return request;}).header("Authorization","Catalog test-only-credential")).andExpect(status().isForbidden());
    mvc.perform(get("/api/external/v1/catalog/session").secure(true).with(request->{request.setRemoteAddr("192.0.2.10");return request;}).header("Authorization","Catalog test-only-credential")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    mvc.perform(get("/api/external/v1/catalog/session").with(request->{request.setServerName("api.example.test");request.setRemoteAddr("127.0.0.1");return request;}).header("Authorization","Catalog test-only-credential")).andExpect(status().isForbidden());
  }
}
