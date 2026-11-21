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
    var publicationId1 = p.id();
    assertThrows(ResponseStatusException.class,()-> f.external.read(bIdentity, publicationId1, 1, PLAN2, null, 0, 20));
  }
  @Test void priceIsReadFromApprovedMarketConfigurationWithoutFinancialRecalculation() {
    var p=f.published(ApiPublication.CatalogType.PRICE,1); var key=f.credential(p.id(),B); var result=f.external.read(f.external.authenticate(key.credential()),p.id(),1,null,null,0,20);
    assertEquals(new java.math.BigDecimal("12.10"),result.content().get(0).price().totalPrice()); assertEquals("USD",result.content().get(0).price().currency());
    assertFalse(f.db.collections.containsKey("invoice")); assertFalse(f.db.collections.containsKey("submission")); assertFalse(f.db.collections.containsKey("actual_metrics"));
  }
  @Test void revokedPartnerCannotAuthenticateOrContinueWithPreviouslyAuthenticatedPrincipal() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var key=f.credential(p.id(),B); var identity=f.external.authenticate(key.credential());
    var current=f.publications.detail(p.id()); f.publications.revoke(p.id(),B,new CatalogDtos.Revision(current.revision()));
    var credential1 = key.credential();
    assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,()-> f.external.authenticate(credential1)).getStatusCode());
    var publicationId2 = p.id();
    assertThrows(ResponseStatusException.class,()-> f.external.read(identity, publicationId2, 1, null, null, 0, 20));
    assertTrue(f.publications.detail(p.id()).audit().stream().anyMatch(a->a.action().equals("REVOKE_PARTNER_ACCESS")));
  }
  @Test void credentialRotationInvalidatesOldCredentialWithoutAffectingOtherPartner() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var old=f.credential(p.id(),B); var other=f.credential(p.id(),C); var next=f.credential(p.id(),B);
    assertNotEquals(old.credential(),next.credential());
    var credential2 = old.credential();
    assertThrows(ResponseStatusException.class,()-> f.external.authenticate(credential2)); assertNotNull(f.external.authenticate(next.credential())); assertNotNull(f.external.authenticate(other.credential()));
    assertTrue(next.toString().contains("REDACTED")); assertFalse(next.toString().contains(next.credential()));
  }
  @Test void invalidExpiredOrInternalJwtCredentialsAreRejected() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var key=f.credential(p.id(),B);
    for(String invalid:List.of("Bearer internal.jwt.token","Catalog invalid",key.credential().substring(0,key.credential().length()-1)+"!")) assertThrows(ResponseStatusException.class,()->f.external.authenticate(invalid));
    AtomicCatalogMongo.put(f.db.raw(ApiPublication.class,p.id()),"grants."+B+".expiresAt",Date.from(Instant.now().minusSeconds(1)),false);
    var credential3 = key.credential();
    assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,()-> f.external.authenticate(credential3)).getStatusCode());
  }
  @Test void draftAndRetiredPublicationsNeverServePartnerRequests() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var key=f.credential(p.id(),B); var identity=f.external.authenticate(key.credential());
    f.db.raw(ApiPublication.class,p.id()).put("status","DRAFT");
    var publicationId3 = p.id();
    assertThrows(ResponseStatusException.class,()-> f.external.read(identity, publicationId3, 1, null, null, 0, 20));
    f.db.raw(ApiPublication.class,p.id()).put("status","RETIRED");
    var credential4 = key.credential();
    assertThrows(ResponseStatusException.class,()-> f.external.authenticate(credential4));
  }
  @Test void grantsCannotBroadenPlanScopeOrGrantToNonEcosystemPartner() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1);
    var publicationId4 = p.id();
    var grant1 = new CatalogDtos.Grant(p.revision(),B,List.of(PLAN2),Instant.now().plusSeconds(60));
    assertThrows(ResponseStatusException.class,()-> f.publications.grant(publicationId4, grant1));
    when(f.tenants.propositionReference(B)).thenReturn(tenant(B,com.ecofood.core.enums.PartnerTypeEnum.BRAND));
    var publicationId5 = p.id();
    var grant2 = new CatalogDtos.Grant(p.revision(),B,null,Instant.now().plusSeconds(60));
    assertThrows(ResponseStatusException.class,()-> f.publications.grant(publicationId5, grant2));
  }
  @Test void revocationDuringSourceResolutionWinsAtAuthorizationFence() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var key=f.credential(p.id(),B); var identity=f.external.authenticate(key.credential());
    doAnswer(call->{ AtomicCatalogMongo.put(f.db.raw(ApiPublication.class,p.id()),"grants."+B+".revokedAt",Date.from(Instant.now()),false); return null; }).when(f.parties).validate(f.proposition);
    var publicationId6 = p.id();
    assertThrows(ResponseStatusException.class,()-> f.external.read(identity, publicationId6, 1, null, null, 0, 20)); assertEquals(0,f.publications.detail(p.id()).accessCount());
  }
  @Test void publisherAuthorityOrPartnerDistributionLossImmediatelyBlocksCatalog() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var key=f.credential(p.id(),B); var identity=f.external.authenticate(key.credential());
    f.proposition.getPlans().get(0).setTenantIds(List.of(A));
    var publicationId7 = p.id();
    assertThrows(ResponseStatusException.class,()-> f.external.read(identity, publicationId7, 1, null, null, 0, 20));
    when(f.access.publisher(USER,A)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
    var credential5 = key.credential();
    assertThrows(ResponseStatusException.class,()-> f.external.authenticate(credential5));
  }
  @Test void publicationAndSourceExpiryOrModifiedPriceDoNotSilentlyChangeHistoricalResponse() {
    var p=f.published(ApiPublication.CatalogType.PRICE,1); var key=f.credential(p.id(),B); var identity=f.external.authenticate(key.credential());
    var oldPins=f.publications.detail(p.id()).pins(); f.proposition.getPlans().get(0).setName("A later name");
    // Plan name is not part of the public data contract; non-public metadata alone does not change the API.
    assertNotNull(f.external.read(identity,p.id(),1,null,null,0,20));
    f.proposition.getPlans().get(0).setProductOverrides(new com.ecofood.core.dto.PlanProductOverrides("Changed publicly",null,null));
    var publicationId8 = p.id();
    assertThrows(ResponseStatusException.class,()-> f.external.read(identity, publicationId8, 1, null, null, 0, 20)); assertEquals(oldPins,f.publications.detail(p.id()).pins());
    assertEquals(B,f.publications.detail(p.id()).lastDeniedPartnerId()); assertEquals("SOURCE_NOT_PUBLISHABLE",f.publications.detail(p.id()).lastDeniedCode());
    f.proposition.getPlans().get(0).setEndDate(Instant.now().minusSeconds(1));
    var publicationId9 = p.id();
    assertThrows(ResponseStatusException.class,()-> f.external.read(identity, publicationId9, 1, null, null, 0, 20));
    AtomicCatalogMongo.put(f.db.raw(ApiPublication.class,p.id()),"policy.expiresAt",Date.from(Instant.now().minusSeconds(1)),false);
    var credential6 = key.credential();
    assertThrows(ResponseStatusException.class,()-> f.external.authenticate(credential6));
  }
  @Test void versionAndPublicationScopeCannotBeSelectedByKnowingAnIdentifier() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); var next=f.published(ApiPublication.CatalogType.PRODUCT,2); var identity=f.external.authenticate(f.credential(p.id(),B).credential());
    var publicationId10 = next.id();
    assertThrows(ResponseStatusException.class,()-> f.external.read(identity, publicationId10, 2, null, null, 0, 20));
    var publicationId11 = p.id();
    assertThrows(ResponseStatusException.class,()-> f.external.read(identity, publicationId11, 2, null, null, 0, 20));
    var publicationId12 = p.id();
    assertThrows(ResponseStatusException.class,()-> f.external.read(identity, publicationId12, 1, null, null, 0, 21));
    assertTrue(f.external.read(identity,p.id(),1,null,"no match",0,1).content().isEmpty());
  }
}
