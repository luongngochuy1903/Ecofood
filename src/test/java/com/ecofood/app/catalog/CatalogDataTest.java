package com.ecofood.app.catalog;

import static com.ecofood.app.catalog.CatalogFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.catalog.security.CatalogAccess;
import com.ecofood.core.dto.CatalogDtos;
import com.ecofood.core.enums.CatalogTypeEnum;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class CatalogDataTest {
  CatalogFixtures f;
  @BeforeEach void setup() throws Exception { f=new CatalogFixtures(); }
  @Test void pinnedApprovedProcessIsUsedEvenWhenLatestProductVersionIsDraft() {
    var resolved=f.data.resolve(f.actor,new CatalogDtos.Selection(PROP,PLAN1),CatalogTypeEnum.PRODUCT);
    // The item's displayed name/description/image now come from the Plan's OWN
    // clone, not the Product's definition — only the Product process identity
    // (version/id) is still read off the source Product for eligibility.
    assertEquals(4,resolved.pin().productVersion()); assertEquals(PROCESS,resolved.pin().productProcessId()); assertEquals("Plan "+PLAN1,resolved.item().name());
  }
  @Test void productDraftProcessAndMismatchedDefinitionRevisionCannotBePublished() {
    var raw=f.db.raw(com.ecofood.app.product.entity.ProductProcessEntity.class,new ObjectId(PROCESS)); raw.put("status","DRAFT");
    var selection1 = new CatalogDtos.Selection(PROP,PLAN1);
    assertThrows(ResponseStatusException.class,()-> f.data.resolve(f.actor, selection1, CatalogTypeEnum.PRODUCT));
    raw.put("status","APPROVED"); f.db.raw(com.ecofood.app.product.entity.ProductDefinitionAttribute.class,new ObjectId(DEFINITION)).put("processRevision",99L);
    var selection2 = new CatalogDtos.Selection(PROP,PLAN1);
    assertThrows(ResponseStatusException.class,()-> f.data.resolve(f.actor, selection2, CatalogTypeEnum.PRODUCT));
  }
  @Test void productDataAccessRulesAndTenantDistributionAreEnforcedAtSource() {
    var restricted=new CatalogAccess.Actor(USER,f.actor.tenant(),Set.of("different-product"),f.actor.permissions());
    var selection3 = new CatalogDtos.Selection(PROP,PLAN1);
    assertThrows(ResponseStatusException.class,()-> f.data.resolve(restricted, selection3, CatalogTypeEnum.PRODUCT));
    f.proposition.getPlans().get(0).setTenantIds(List.of(B));
    var selection4 = new CatalogDtos.Selection(PROP,PLAN1);
    assertThrows(ResponseStatusException.class,()-> f.data.resolve(f.actor, selection4, CatalogTypeEnum.PRODUCT));
  }
  @Test void missingPriceDoesNotFallBackToProductCostingOrInventAnAmount() {
    f.db.rows(com.ecofood.app.product.entity.ProductCostingAttribute.class).values().iterator().next().put("sellingPrice",null);
    var selection5 = new CatalogDtos.Selection(PROP,PLAN1);
    assertThrows(ResponseStatusException.class,()-> f.data.resolve(f.actor, selection5, CatalogTypeEnum.PRICE));
    assertNull(f.data.resolve(f.actor,new CatalogDtos.Selection(PROP,PLAN1),CatalogTypeEnum.PRODUCT).item().price());
  }
  @Test void supersededPlanIsDeniedRatherThanAutomaticallyReboundToNewPlan() {
    var newer=plan("dddddddddddddddddddddddd",List.of(A,B),f.db); newer.setVersion(2); newer.setPreviousVersionId(PLAN1); f.proposition.setPlans(List.of(f.proposition.getPlans().get(0),newer));
    var selection6 = new CatalogDtos.Selection(PROP,PLAN1);
    assertThrows(ResponseStatusException.class,()-> f.data.resolve(f.actor, selection6, CatalogTypeEnum.PRODUCT));
    assertEquals(2,f.data.resolve(f.actor,new CatalogDtos.Selection(PROP,newer.getIdAsString()),CatalogTypeEnum.PRODUCT).pin().planVersion());
  }
  @Test void optionsExposeOnlyRealSupportedCatalogsAndEligiblePartners() {
    var options=f.data.options(f.actor,CatalogTypeEnum.PRODUCT); assertEquals(List.of(CatalogTypeEnum.PRODUCT,CatalogTypeEnum.PRICE),options.catalogTypes());
    assertEquals(List.of("VOUCHER","COMBO"),options.unsupportedCatalogs());
    assertTrue(options.partners().stream().allMatch(p->List.of(A,B,C).contains(p.id())));
  }
}
