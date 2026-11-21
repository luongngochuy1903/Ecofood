package com.ecofood.app.catalog;

import static com.ecofood.app.catalog.CatalogFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CatalogExternalTest {
  CatalogFixtures f;
  @BeforeEach void setup() throws Exception { f=new CatalogFixtures(); }
  @Test void partnerReadsOnlyGrantedPlanWhileOtherPartnerReadsDifferentScope() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var b=f.credential(p.id(),B); var c=f.credential(p.id(),C);
    var bIdentity=f.external.authenticate(b.credential()); var cIdentity=f.external.authenticate(c.credential());
    var first=f.external.read(bIdentity,p.id(),1,null,null,0,20); var second=f.external.read(cIdentity,p.id(),1,null,null,0,20);
    assertEquals(List.of(PLAN1),first.content().stream().map(CatalogDtos.Item::planId).toList()); assertEquals(List.of(PLAN2),second.content().stream().map(CatalogDtos.Item::planId).toList());
    assertNull(first.content().get(0).price()); assertEquals(4,first.content().get(0).productVersion()); assertEquals(2,f.publications.detail(p.id()).accessCount());
    assertThrows(ResponseStatusException.class,()->f.external.read(bIdentity,p.id(),1,PLAN2,null,0,20));
  }
  @Test void priceIsReadFromApprovedMarketConfigurationWithoutFinancialRecalculation() {
    var p=f.published(ApiPublication.CatalogType.PRICE,1); var key=f.credential(p.id(),B); var result=f.external.read(f.external.authenticate(key.credential()),p.id(),1,null,null,0,20);
    assertEquals(new java.math.BigDecimal("12.10"),result.content().get(0).price().totalPrice()); assertEquals("USD",result.content().get(0).price().currency());
    assertFalse(f.db.collections.containsKey("invoice")); assertFalse(f.db.collections.containsKey("submission")); assertFalse(f.db.collections.containsKey("actual_metrics"));
  }
  @Test void revokedPartnerCannotAuthenticateOrContinueWithPreviouslyAuthenticatedPrincipal() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var key=f.credential(p.id(),B); var identity=f.external.authenticate(key.credential());
    var current=f.publications.detail(p.id()); f.publications.revoke(p.id(),B,new CatalogDtos.Revision(current.revision()));
    assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,()->f.external.authenticate(key.credential())).getStatusCode());
    assertThrows(ResponseStatusException.class,()->f.external.read(identity,p.id(),1,null,null,0,20));
    assertTrue(f.publications.detail(p.id()).audit().stream().anyMatch(a->a.action().equals("REVOKE_PARTNER_ACCESS")));
  }
  @Test void credentialRotationInvalidatesOldCredentialWithoutAffectingOtherPartner() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var old=f.credential(p.id(),B); var other=f.credential(p.id(),C); var next=f.credential(p.id(),B);
    assertNotEquals(old.credential(),next.credential()); assertThrows(ResponseStatusException.class,()->f.external.authenticate(old.credential())); assertNotNull(f.external.authenticate(next.credential())); assertNotNull(f.external.authenticate(other.credential()));
    assertTrue(next.toString().contains("REDACTED")); assertFalse(next.toString().contains(next.credential()));
  }
  @Test void invalidExpiredOrInternalJwtCredentialsAreRejected() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var key=f.credential(p.id(),B);
    for(String invalid:List.of("Bearer internal.jwt.token","Catalog invalid",key.credential().substring(0,key.credential().length()-1)+"!")) assertThrows(ResponseStatusException.class,()->f.external.authenticate(invalid));
    AtomicCatalogMongo.put(f.db.raw(ApiPublication.class,p.id()),"grants."+B+".expiresAt",Date.from(Instant.now().minusSeconds(1)),false);
    assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,()->f.external.authenticate(key.credential())).getStatusCode());
  }
  @Test void draftAndRetiredPublicationsNeverServePartnerRequests() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var key=f.credential(p.id(),B); var identity=f.external.authenticate(key.credential());
    f.db.raw(ApiPublication.class,p.id()).put("status","DRAFT"); assertThrows(ResponseStatusException.class,()->f.external.read(identity,p.id(),1,null,null,0,20));
    f.db.raw(ApiPublication.class,p.id()).put("status","RETIRED"); assertThrows(ResponseStatusException.class,()->f.external.authenticate(key.credential()));
  }
  @Test void grantsCannotBroadenPlanScopeOrGrantToNonEcosystemPartner() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1);
    assertThrows(ResponseStatusException.class,()->f.publications.grant(p.id(),new CatalogDtos.Grant(p.revision(),B,List.of(PLAN2),Instant.now().plusSeconds(60))));
    when(f.tenants.propositionReference(B)).thenReturn(tenant(B,com.ecofood.core.enums.PartnerTypeEnum.BRAND));
    assertThrows(ResponseStatusException.class,()->f.publications.grant(p.id(),new CatalogDtos.Grant(p.revision(),B,null,Instant.now().plusSeconds(60))));
  }
  @Test void revocationDuringSourceResolutionWinsAtAuthorizationFence() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var key=f.credential(p.id(),B); var identity=f.external.authenticate(key.credential());
    doAnswer(call->{ AtomicCatalogMongo.put(f.db.raw(ApiPublication.class,p.id()),"grants."+B+".revokedAt",Date.from(Instant.now()),false); return null; }).when(f.parties).validate(f.proposition);
    assertThrows(ResponseStatusException.class,()->f.external.read(identity,p.id(),1,null,null,0,20)); assertEquals(0,f.publications.detail(p.id()).accessCount());
  }
  @Test void publisherAuthorityOrPartnerDistributionLossImmediatelyBlocksCatalog() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var key=f.credential(p.id(),B); var identity=f.external.authenticate(key.credential());
    f.proposition.getPlans().get(0).setTenantIds(List.of(A)); assertThrows(ResponseStatusException.class,()->f.external.read(identity,p.id(),1,null,null,0,20));
    when(f.access.publisher(USER,A)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)); assertThrows(ResponseStatusException.class,()->f.external.authenticate(key.credential()));
  }
  @Test void publicationAndSourceExpiryOrModifiedPriceDoNotSilentlyChangeHistoricalResponse() {
    var p=f.published(ApiPublication.CatalogType.PRICE,1); var key=f.credential(p.id(),B); var identity=f.external.authenticate(key.credential());
    var oldPins=f.publications.detail(p.id()).pins(); f.proposition.getPlans().get(0).setName("A later name");
    // Plan name is not part of the public data contract; non-public metadata alone does not change the API.
    assertNotNull(f.external.read(identity,p.id(),1,null,null,0,20));
    f.proposition.getPlans().get(0).setProductOverrides(new com.ecofood.core.dto.PlanProductOverrides("Changed publicly",null,null));
    assertThrows(ResponseStatusException.class,()->f.external.read(identity,p.id(),1,null,null,0,20)); assertEquals(oldPins,f.publications.detail(p.id()).pins());
    assertEquals(B,f.publications.detail(p.id()).lastDeniedPartnerId()); assertEquals("SOURCE_NOT_PUBLISHABLE",f.publications.detail(p.id()).lastDeniedCode());
    f.proposition.getPlans().get(0).setEndDate(Instant.now().minusSeconds(1)); assertThrows(ResponseStatusException.class,()->f.external.read(identity,p.id(),1,null,null,0,20));
    AtomicCatalogMongo.put(f.db.raw(ApiPublication.class,p.id()),"policy.expiresAt",Date.from(Instant.now().minusSeconds(1)),false); assertThrows(ResponseStatusException.class,()->f.external.authenticate(key.credential()));
  }
  @Test void versionAndPublicationScopeCannotBeSelectedByKnowingAnIdentifier() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var next=f.published(ApiPublication.CatalogType.PRODUCT,2); var identity=f.external.authenticate(f.credential(p.id(),B).credential());
    assertThrows(ResponseStatusException.class,()->f.external.read(identity,next.id(),2,null,null,0,20)); assertThrows(ResponseStatusException.class,()->f.external.read(identity,p.id(),2,null,null,0,20));
    assertThrows(ResponseStatusException.class,()->f.external.read(identity,p.id(),1,null,null,0,21));
    assertTrue(f.external.read(identity,p.id(),1,null,"no match",0,1).content().isEmpty());
  }
}
