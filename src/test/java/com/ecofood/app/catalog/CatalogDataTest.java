package com.ecofood.app.catalog;

import static com.ecofood.app.catalog.CatalogFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class CatalogDataTest {
  CatalogFixtures f;
  @BeforeEach void setup() throws Exception { f=new CatalogFixtures(); }
  @Test void pinnedApprovedProcessIsUsedEvenWhenLatestProductVersionIsDraft() {
    var resolved=f.data.resolve(f.actor,new CatalogDtos.Selection(PROP,PLAN1),ApiPublication.CatalogType.PRODUCT);
    assertEquals(4,resolved.pin().productVersion()); assertEquals(PROCESS,resolved.pin().productProcessId()); assertEquals("Approved Food",resolved.item().name());
    assertFalse(f.db.collections.containsKey("product_costing_attribute")); assertFalse(f.db.collections.containsKey("product_overview_attribute"));
  }
  @Test void productDraftProcessAndMismatchedDefinitionRevisionCannotBePublished() {
    var raw=f.db.raw(com.ecofood.app.product.entity.ProductProcessEntity.class,new ObjectId(PROCESS)); raw.put("status","DRAFT");
    assertThrows(ResponseStatusException.class,()->f.data.resolve(f.actor,new CatalogDtos.Selection(PROP,PLAN1),ApiPublication.CatalogType.PRODUCT));
    raw.put("status","APPROVED"); f.db.raw(com.ecofood.app.product.entity.ProductDefinitionAttribute.class,new ObjectId(DEFINITION)).put("processRevision",99L);
    assertThrows(ResponseStatusException.class,()->f.data.resolve(f.actor,new CatalogDtos.Selection(PROP,PLAN1),ApiPublication.CatalogType.PRODUCT));
  }
  @Test void productDataAccessRulesAndTenantDistributionAreEnforcedAtSource() {
    var restricted=new CatalogAccess.Actor(USER,f.actor.tenant(),Set.of("different-product"),f.actor.permissions());
    assertThrows(ResponseStatusException.class,()->f.data.resolve(restricted,new CatalogDtos.Selection(PROP,PLAN1),ApiPublication.CatalogType.PRODUCT));
    f.proposition.getPlans().get(0).setTenantIds(List.of(B)); assertThrows(ResponseStatusException.class,()->f.data.resolve(f.actor,new CatalogDtos.Selection(PROP,PLAN1),ApiPublication.CatalogType.PRODUCT));
  }
  @Test void missingPriceDoesNotFallBackToProductCostingOrInventAnAmount() {
    f.proposition.getPlans().get(0).setMarketConfiguration(null);
    assertThrows(ResponseStatusException.class,()->f.data.resolve(f.actor,new CatalogDtos.Selection(PROP,PLAN1),ApiPublication.CatalogType.PRICE));
    assertNull(f.data.resolve(f.actor,new CatalogDtos.Selection(PROP,PLAN1),ApiPublication.CatalogType.PRODUCT).item().price());
  }
  @Test void supersededPlanIsDeniedRatherThanAutomaticallyReboundToNewPlan() {
    var newer=plan("dddddddddddddddddddddddd",List.of(A,B)); newer.setVersion(2); newer.setPreviousVersionId(PLAN1); f.proposition.setPlans(List.of(f.proposition.getPlans().get(0),newer));
    assertThrows(ResponseStatusException.class,()->f.data.resolve(f.actor,new CatalogDtos.Selection(PROP,PLAN1),ApiPublication.CatalogType.PRODUCT));
    assertEquals(2,f.data.resolve(f.actor,new CatalogDtos.Selection(PROP,newer.getIdAsString()),ApiPublication.CatalogType.PRODUCT).pin().planVersion());
  }
  @Test void optionsExposeOnlyRealSupportedCatalogsAndEligiblePartners() {
    var options=f.data.options(f.actor,ApiPublication.CatalogType.PRODUCT); assertEquals(List.of(ApiPublication.CatalogType.PRODUCT,ApiPublication.CatalogType.PRICE),options.catalogTypes());
    assertEquals(List.of("VOUCHER","COMBO"),options.unsupportedCatalogs());
    assertTrue(options.partners().stream().allMatch(p->List.of(A,B,C).contains(p.id())));
  }
}
