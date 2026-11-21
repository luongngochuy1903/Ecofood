package com.ecofood.app.catalog;

import static com.ecofood.app.catalog.CatalogFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CatalogLifecycleTest {
  CatalogFixtures f;
  @BeforeEach void setup() throws Exception { f=new CatalogFixtures(); }
  @Test void draftValidatePublishPinsExactProcessAndPlanVersionsWithAudit() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1);
    assertEquals(ApiPublication.Status.PUBLISHED,p.status()); assertEquals(2,p.pins().size()); assertEquals(4,p.pins().get(0).productVersion()); assertEquals(1,p.pins().get(0).planVersion()); assertEquals(2L,p.pins().get(0).processRevision());
    assertTrue(p.endpoint().endsWith("/versions/1")); assertEquals(List.of("CREATE_DRAFT","VALIDATE","PUBLISH"),p.audit().stream().map(ApiPublication.Audit::action).toList()); assertTrue(p.grants().isEmpty());
    assertEquals(Set.of("product","product_process","product_definition_attribute","api_publication"),f.db.collections.keySet());
  }
  @Test void permissionDeniedCannotSavePublishOrGrant() {
    var draft=f.draft(ApiPublication.CatalogType.PRODUCT,1);
    when(f.access.current()).thenReturn(new CatalogAccess.Actor(USER,tenant(A,com.ecofood.core.enums.PartnerTypeEnum.ECOSYSTEM_PARTNER),null,Set.of(CatalogAccess.READ)));
    assertThrows(ResponseStatusException.class,()->f.publications.save(null,draft)); assertTrue(f.db.rows(ApiPublication.class).isEmpty());
  }
  @Test void duplicateTenantApiTypeVersionRejectedWithoutOverwritingFirstScope() {
    var draft=f.draft(ApiPublication.CatalogType.PRODUCT,1); var first=f.publications.save(null,draft);
    assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,()->f.publications.save(null,draft)).getStatusCode()); assertEquals(first.id(),f.publications.detail(first.id()).id()); assertEquals(1,f.db.rows(ApiPublication.class).size());
  }
  @Test void approvedSourceRequiredAndFailedValidationIsAudited() {
    var p=f.publications.save(null,f.draft(ApiPublication.CatalogType.PRODUCT,1)); f.proposition.setStatus(com.ecofood.core.enums.PropositionStatusEnum.DRAFT);
    var publicationId1 = p.id();
    var revision1 = new CatalogDtos.Revision(p.revision());
    assertThrows(ResponseStatusException.class,()-> f.publications.validate(publicationId1, revision1));
    var failed=f.publications.detail(p.id()); assertEquals(ApiPublication.Status.DRAFT,failed.status()); assertEquals("FAILED",failed.audit().get(failed.audit().size()-1).result());
  }
  @Test void updateResetsValidationAndPublishedConfigurationCannotBeEdited() {
    var d=f.draft(ApiPublication.CatalogType.PRODUCT,1); var p=f.publications.save(null,d); p=f.publications.validate(p.id(),new CatalogDtos.Revision(p.revision()));
    var edited=f.publications.save(p.id(),new CatalogDtos.Draft(d.apiKey(),"Edited",d.catalogType(),d.version(),d.selections(),d.partnerIds(),d.policy(),d.customField(),p.revision())); assertEquals(ApiPublication.Status.DRAFT,edited.status()); assertTrue(edited.pins().isEmpty());
    edited=f.publications.validate(edited.id(),new CatalogDtos.Revision(edited.revision())); var pub=f.publications.publish(edited.id(),new CatalogDtos.Revision(edited.revision()));
    var publicationId2 = pub.id();
    var draft1 = new CatalogDtos.Draft(d.apiKey(),d.name(),d.catalogType(),d.version(),d.selections(),d.partnerIds(),d.policy(),d.customField(),pub.revision());
    assertThrows(ResponseStatusException.class,()-> f.publications.save(publicationId2, draft1));
  }
  @Test void sourceChangeBetweenValidateAndPublishRequiresRevalidation() {
    var p=f.publications.save(null,f.draft(ApiPublication.CatalogType.PRICE,1)); var v=f.publications.validate(p.id(),new CatalogDtos.Revision(p.revision()));
    f.proposition.getPlans().get(0).setProductOverrides(new com.ecofood.core.dto.PlanProductOverrides("Changed",null,null));
    var publicationId3 = v.id();
    var revision2 = new CatalogDtos.Revision(v.revision());
    assertThrows(ResponseStatusException.class,()-> f.publications.publish(publicationId3, revision2)); assertEquals(ApiPublication.Status.VALIDATED,f.publications.detail(v.id()).status());
  }
  @Test void newVersionPublishesInParallelAndRetirementDoesNotRetireOldVersion() {
    var first=f.published(ApiPublication.CatalogType.PRODUCT,1); var second=f.published(ApiPublication.CatalogType.PRODUCT,2);
    assertNotEquals(first.id(),second.id()); assertEquals(ApiPublication.Status.PUBLISHED,f.publications.detail(first.id()).status());
    assertTrue(second.audit().stream().anyMatch(a->"PUBLISH_NEW_VERSION".equals(a.action())));
    f.publications.retire(second.id(),new CatalogDtos.Revision(second.revision())); assertEquals(ApiPublication.Status.PUBLISHED,f.publications.detail(first.id()).status());
  }
  @Test void crossTenantCannotInspectVersionsAuditOrRetire() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); when(f.access.current()).thenReturn(new CatalogAccess.Actor(USER,tenant(B,com.ecofood.core.enums.PartnerTypeEnum.ECOSYSTEM_PARTNER),null,f.actor.permissions()));
    var publicationId4 = p.id();
    assertThrows(ResponseStatusException.class,()-> f.publications.detail(publicationId4));
    var publicationId5 = p.id();
    var revision3 = new CatalogDtos.Revision(p.revision());
    assertThrows(ResponseStatusException.class,()-> f.publications.retire(publicationId5, revision3)); assertTrue(f.publications.list(null,null,0,20).content().isEmpty());
  }
  @Test void simultaneousPublishCommitsOneActivationAndAudit() throws Exception {
    var d=f.publications.save(null,f.draft(ApiPublication.CatalogType.PRODUCT,1)); var p=f.publications.validate(d.id(),new CatalogDtos.Revision(d.revision()));
    var start=new CountDownLatch(1); ExecutorService pool=Executors.newFixedThreadPool(2);
    try { Callable<Boolean> job=()->{start.await();try {f.publications.publish(p.id(),new CatalogDtos.Revision(p.revision()));return true;}catch(ResponseStatusException conflict){return false;}};
      var a=pool.submit(job); var b=pool.submit(job); start.countDown(); assertNotEquals(a.get(),b.get());
      assertEquals(1,f.publications.detail(p.id()).audit().stream().filter(audit->audit.action().equals("PUBLISH")).count());
    } finally { pool.shutdownNow(); }
  }
  @Test void managePermissionDoesNotImplyPublishOrGrantPermission() {
    when(f.access.current()).thenReturn(new CatalogAccess.Actor(USER,f.actor.tenant(),null,Set.of(CatalogAccess.READ,CatalogAccess.MANAGE)));
    var draft=f.publications.save(null,f.draft(ApiPublication.CatalogType.PRODUCT,1));
    var publicationId6 = draft.id();
    var revision4 = new CatalogDtos.Revision(draft.revision());
    assertThrows(ResponseStatusException.class,()-> f.publications.validate(publicationId6, revision4));
    var publicationId7 = draft.id();
    var revision5 = new CatalogDtos.Revision(draft.revision());
    assertThrows(ResponseStatusException.class,()-> f.publications.publish(publicationId7, revision5));
    var publicationId8 = draft.id();
    var grant1 = new CatalogDtos.Grant(draft.revision(),B,null,java.time.Instant.now().plusSeconds(60));
    assertThrows(ResponseStatusException.class,()-> f.publications.grant(publicationId8, grant1));
  }
  @Test void auditCapacityNeverPreventsSecurityRevocationOrRetirement() {
    var p=f.published(ApiPublication.CatalogType.PRODUCT,1); f.credential(p.id(),B);
    var stored=f.db.findByIdForTest(p.id()); var seed=stored.getAudit().get(0); var converter=f.db.converter; var event=new org.bson.Document(); converter.write(seed,event);
    f.db.raw(ApiPublication.class,p.id()).put("audit",new ArrayList<>(java.util.Collections.nCopies(2000,event)));
    var current=f.publications.detail(p.id()); var revoked=f.publications.revoke(p.id(),B,new CatalogDtos.Revision(current.revision()));
    assertNotNull(revoked.grants().get(0).revokedAt()); assertEquals(ApiPublication.Status.RETIRED,f.publications.retire(p.id(),new CatalogDtos.Revision(revoked.revision())).status());
  }
}
