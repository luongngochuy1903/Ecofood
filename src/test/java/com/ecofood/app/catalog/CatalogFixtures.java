package com.ecofood.app.catalog;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.catalog.security.CatalogAccess;
import com.ecofood.app.catalog.security.CatalogExternalAccess;
import com.ecofood.app.catalog.service.CatalogData;
import com.ecofood.app.catalog.service.CatalogPublicationServiceImpl;
import com.ecofood.app.product.entity.*;
import com.ecofood.app.product.entity.ProductDefinition;
import com.ecofood.app.product.entity.ProductDefinitionAttribute;
import com.ecofood.app.product.entity.ProductEntity;
import com.ecofood.app.product.entity.ProductProcessEntity;
import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.entity.Plan;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.repository.PropositionRepository;
import com.ecofood.app.proposition.service.PropositionParties;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.*;
import com.ecofood.core.dto.CatalogDtos;
import com.ecofood.core.dto.MarketConfigurationResponse;
import com.ecofood.core.dto.PropositionDefinition;
import com.ecofood.core.dto.PropositionTenantReference;
import com.ecofood.core.enums.*;
import com.ecofood.core.enums.CatalogTypeEnum;
import com.ecofood.core.enums.PartnerTypeEnum;
import com.ecofood.core.enums.PlanStatusEnum;
import com.ecofood.core.enums.ProductStatusEnum;
import com.ecofood.core.enums.PropositionStatusEnum;
import com.ecofood.core.enums.TenantTypeEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.bson.types.ObjectId;

final class CatalogFixtures {
  static final String A="111111111111111111111111", B="222222222222222222222222", C="333333333333333333333333", USER="444444444444444444444444",
      ENTERPRISE="555555555555555555555555", PRODUCT="666666666666666666666666", PROCESS="777777777777777777777777", DEFINITION="888888888888888888888888", PROP="999999999999999999999999",
      PLAN1="aaaaaaaaaaaaaaaaaaaaaaaa", PLAN2="bbbbbbbbbbbbbbbbbbbbbbbb", OWNER="cccccccccccccccccccccccc";
  static PropositionTenantReference tenant(String id,PartnerTypeEnum type) { return new PropositionTenantReference(id,"Tenant "+id,null,type,TenantTypeEnum.SUB_TENANT,List.of(),List.of(),false); }
  final AtomicCatalogMongo db;
  final CatalogAccess access=mock(CatalogAccess.class);
  final CoreTenantQueryClient tenants=mock(CoreTenantQueryClient.class);
  final PropositionRepository propositions=mock(PropositionRepository.class);
  final PropositionParties parties=mock(PropositionParties.class);
  final CatalogAccess.Actor actor=new CatalogAccess.Actor(USER,tenant(A,PartnerTypeEnum.ECOSYSTEM_PARTNER),null,Set.of(CatalogAccess.READ,CatalogAccess.MANAGE,CatalogAccess.PUBLISH,CatalogAccess.GRANT));
  final PropositionEntity proposition;
  final CatalogData data;
  final CatalogPublicationServiceImpl publications;
  final CatalogExternalAccess external;
  CatalogFixtures() throws Exception {
    db=new AtomicCatalogMongo(); when(access.current()).thenReturn(actor); when(access.publisher(USER,A)).thenReturn(actor);
    when(tenants.propositionReference(A)).thenReturn(tenant(A,PartnerTypeEnum.ECOSYSTEM_PARTNER)); when(tenants.propositionReference(B)).thenReturn(tenant(B,PartnerTypeEnum.ECOSYSTEM_PARTNER)); when(tenants.propositionReference(C)).thenReturn(tenant(C,PartnerTypeEnum.ECOSYSTEM_PARTNER));
    proposition=new PropositionEntity(); proposition.setId(new ObjectId(PROP)); proposition.setEnterpriseId(new ObjectId(ENTERPRISE)); proposition.setOwningTenantId(new ObjectId(OWNER)); proposition.setProductId(new ObjectId(PRODUCT)); proposition.setProductProcessId(new ObjectId(PROCESS)); proposition.setProductVersion(4); proposition.setStatus(PropositionStatusEnum.APPROVED);
    proposition.setDefinition(new PropositionDefinition("Proposition","Description",List.of("VN"),null,List.of(A,B,C),"USD",null,null,null,List.of(),List.of(),new BigDecimal("60"),new BigDecimal("10"),new BigDecimal("30"),Set.of()));
    proposition.setPlans(List.of(plan(PLAN1,List.of(A,B)),plan(PLAN2,List.of(A,C))));
    when(propositions.findById(new ObjectId(PROP))).thenAnswer(ignored->Optional.of(proposition));
    when(propositions.findByDefinitionDistributionPartnerIdsInOrDefinitionBrandIdInOrPlansTenantIdsIn(anyList(),anyList(),anyList())).thenReturn(List.of(proposition));
    when(parties.eligible(proposition)).thenReturn(List.of(tenant(A,PartnerTypeEnum.ECOSYSTEM_PARTNER),tenant(B,PartnerTypeEnum.ECOSYSTEM_PARTNER),tenant(C,PartnerTypeEnum.ECOSYSTEM_PARTNER)));
    var product=new ProductEntity(); product.setId(new ObjectId(PRODUCT)); product.setEnterpriseId(new ObjectId(ENTERPRISE)); product.setLatestVersion(99); product.setLatestStatus(ProductStatusEnum.DRAFT); db.insert(product);
    var process=new ProductProcessEntity(); process.setId(new ObjectId(PROCESS)); process.setProductId(new ObjectId(PRODUCT)); process.setVersion(4); process.setRevision(2L); process.setStatus(ProductStatusEnum.APPROVED); process.setDefinition(new ProductDefinition("definition","Definition",List.of(new ObjectId(DEFINITION)))); db.insert(process);
    var definition=new ProductDefinitionAttribute(); definition.setId(new ObjectId(DEFINITION)); definition.setProductProcessId(process.getId()); definition.setProcessRevision(2L); definition.setProductName("Approved Food"); definition.setDescription("Public description"); definition.setCode("FOOD-4"); definition.setCategory("Food"); db.insert(definition);
    data=new CatalogData(db.mongo,propositions,parties,tenants); publications=new CatalogPublicationServiceImpl(db.mongo,access,data); external=new CatalogExternalAccess(db.mongo,access,data);
  }
  static Plan plan(String id,List<String> tenants) {
    var plan=new Plan(); plan.setId(new ObjectId(id)); plan.setVersion(1); plan.setName("Plan "+id); plan.setTenantIds(tenants); plan.setStatus(PlanStatusEnum.APPROVED);
    plan.setMarketConfiguration(new MarketConfigurationResponse("USD",new BigDecimal("10.00"),new BigDecimal("10"),new BigDecimal("1.00"),new BigDecimal("1.10"),new BigDecimal("12.10"))); return plan;
  }
  CatalogDtos.Draft draft(CatalogTypeEnum type,int version) {
    return new CatalogDtos.Draft("food-catalog","Catalog",type,version,List.of(new CatalogDtos.Selection(PROP,PLAN1),new CatalogDtos.Selection(PROP,PLAN2)),List.of(B,C),
        new CatalogDtos.Policy(Instant.now().minusSeconds(60),Instant.now().plusSeconds(86400),20),List.of(),null);
  }
  CatalogDtos.View published(CatalogTypeEnum type,int version) {
    var p=publications.save(null,draft(type,version)); p=publications.validate(p.id(),new CatalogDtos.Revision(p.revision())); return publications.publish(p.id(),new CatalogDtos.Revision(p.revision()));
  }
  CatalogDtos.Credential credential(String id,String partner) {
    var current=publications.detail(id); return publications.grant(id,new CatalogDtos.Grant(current.revision(),partner,null,Instant.now().plusSeconds(3600)));
  }
}
