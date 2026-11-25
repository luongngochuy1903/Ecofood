package com.ecofood.app.catalog.service;

import com.ecofood.app.catalog.entity.ApiPublication;
import com.ecofood.app.catalog.security.CatalogAccess;
import com.ecofood.app.product.entity.*;
import com.ecofood.app.product.entity.ProductDefinitionAttribute;
import com.ecofood.app.product.entity.ProductEntity;
import com.ecofood.app.product.entity.ProductProcessEntity;
import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.entity.Plan;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.app.proposition.repository.PropositionRepository;
import com.ecofood.app.proposition.service.*;
import com.ecofood.app.proposition.service.PlanAvailability;
import com.ecofood.app.proposition.service.PropositionParties;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.CatalogDtos;
import com.ecofood.core.dto.MarketConfigurationResponse;
import com.ecofood.core.enums.*;
import com.ecofood.core.enums.CatalogTypeEnum;
import com.ecofood.core.enums.PartnerTypeEnum;
import com.ecofood.core.enums.ProductStatusEnum;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Read adapter only. Approved Plan availability and commercial directory rules remain owned by UC-012/013. */
@Service
@RequiredArgsConstructor
public class CatalogData {
  private final MongoTemplate mongo;
  private final PropositionRepository propositions;
  private final PropositionParties parties;
  private final CoreTenantQueryClient tenants;
  public record Source(PropositionEntity proposition, Plan plan) {}
  public record Resolved(CatalogDtos.Pin pin, CatalogDtos.Item item, List<String> partnerIds) {}
  private Source source(CatalogAccess.Actor actor,CatalogDtos.Selection selection) {
    if(selection==null || !ObjectId.isValid(selection.propositionId()) || !ObjectId.isValid(selection.planId())) throw unavailable();
    var p=propositions.findById(new ObjectId(selection.propositionId())).filter(v->!Boolean.TRUE.equals(v.getDeleted())).orElseThrow(CatalogData::unavailable);
    if(p.getDefinition()==null || p.getPlans()==null || p.getEnterpriseId()==null || p.getProductId()==null) throw unavailable();
    boolean owner=p.getOwningTenantId()!=null && p.getOwningTenantId().toHexString().equals(actor.tenant().id()) && p.getEnterpriseId().toHexString().equals(actor.tenant().enterpriseId());
    var plan=p.getPlans().stream().filter(v->selection.planId().equals(v.getIdAsString()) && !Boolean.TRUE.equals(v.getDeleted())).findFirst().orElseThrow(CatalogData::unavailable);
    if(!owner && (p.getDefinition().distributionPartnerIds()==null || !p.getDefinition().distributionPartnerIds().contains(actor.tenant().id()) || plan.getTenantIds()==null || !plan.getTenantIds().contains(actor.tenant().id()))) throw unavailable();
    actor.product(p.getProductId().toHexString()); return new Source(p,plan);
  }
  public void authorize(CatalogAccess.Actor actor,List<CatalogDtos.Selection> selections) { for(var selection:selections) source(actor,selection); }
  public Resolved resolve(CatalogAccess.Actor actor,CatalogDtos.Selection selection,CatalogTypeEnum type) {
    var source=source(actor,selection);
    var p=source.proposition();
    var plan=source.plan();
    if(!PlanAvailability.active(p,plan,Instant.now())) throw invalid("Proposition and Plan must be approved, effective and not superseded");
    parties.validate(p);
    var eligible=parties.eligible(p).stream().filter(t->!t.deleted() && t.partnerType()==PartnerTypeEnum.ECOSYSTEM_PARTNER
        && plan.getTenantIds()!=null && plan.getTenantIds().contains(t.id())).map(t->t.id()).sorted().toList();
    // Non-owner publishers must retain their current explicit distribution relationship too.
    if(!Objects.equals(actor.tenant().enterpriseId(),p.getEnterpriseId().toHexString())
        && parties.eligible(p).stream().noneMatch(t->t.id().equals(actor.tenant().id()))) throw unavailable();
    var product=mongo.findById(p.getProductId(),ProductEntity.class);
    // The Plan's own bound Product version gates eligibility (it must stay
    // APPROVED and its own definition must still match its approved revision) —
    // the Proposition itself no longer pins any version, so a future change to
    // one Plan's bound version only affects that Plan's family, not the whole
    // Proposition. The catalog's displayed content is still the Plan's OWN full
    // clone, not an "override" layered on top of the Product's definition.
    var process=plan.getProductVersion()==null?null:mongo.findOne(
        org.springframework.data.mongodb.core.query.Query.query(
            org.springframework.data.mongodb.core.query.Criteria.where("product_identifier").is(p.getProductId())
                .and("version").is(plan.getProductVersion())),
        ProductProcessEntity.class);
    requireApprovedSource(p,plan,product,process);
    var sourceDefinition=mongo.findById(process.getDefinition().processElementAttribute().get(0),ProductDefinitionAttribute.class);
    requireDefinition(process,sourceDefinition);
    var planProcess=planProcess(plan);
    requirePlanProcess(plan,planProcess);
    var definition=mongo.findById(planProcess.getDefinition().processElementAttribute().get(0),ProductDefinitionAttribute.class);
    requireDefinition(planProcess,definition);
    MarketConfigurationResponse price=type==CatalogTypeEnum.PRICE?price(planProcess):null;
    var item=new CatalogDtos.Item(p.getIdAsString(),plan.getIdAsString(),plan.getVersion(),product.getIdAsString(),process.getVersion(),
        definition.getCode(),definition.getProductName(),definition.getDescription(),definition.getCategory(),definition.getImagesURL(),price);
    var pin=new CatalogDtos.Pin(p.getIdAsString(),plan.getIdAsString(),plan.getVersion(),product.getIdAsString(),process.getIdAsString(),process.getVersion(),
        process.getRevision(),definition.getIdAsString(),fingerprint(item));
    return new Resolved(pin,item,eligible);
  }
  /** A Plan's own cloned process — always version 1, keyed by the Plan's own id (see CoreProductProcessClient). */
  private ProductProcessEntity planProcess(Plan plan) {
    return mongo.findOne(org.springframework.data.mongodb.core.query.Query.query(
        org.springframework.data.mongodb.core.query.Criteria.where("product_identifier").is(plan.getId()).and("version").is(1)),ProductProcessEntity.class);
  }
  private void requireApprovedSource(PropositionEntity p,Plan plan,ProductEntity product,ProductProcessEntity process) {
    if(product==null || Boolean.TRUE.equals(product.getDeleted()) || !p.getEnterpriseId().equals(product.getEnterpriseId())
        || plan.getProductVersion()==null
        || process==null || Boolean.TRUE.equals(process.getDeleted()) || !p.getProductId().equals(process.getProductId()) || process.getVersion()!=plan.getProductVersion()
        || process.getStatus()!=ProductStatusEnum.APPROVED || process.getRevision()==null || process.getDefinition()==null || process.getDefinition().processElementAttribute()==null
        || process.getDefinition().processElementAttribute().size()!=1) throw invalid("Approved typed ProductProcess source is unavailable");
  }
  private void requirePlanProcess(Plan plan,ProductProcessEntity process) {
    if(process==null || Boolean.TRUE.equals(process.getDeleted()) || !plan.getId().equals(process.getProductId())
        || process.getRevision()==null || process.getDefinition()==null || process.getDefinition().processElementAttribute()==null
        || process.getDefinition().processElementAttribute().size()!=1) throw invalid("Plan product content is unavailable");
  }
  private void requireDefinition(ProductProcessEntity process,ProductDefinitionAttribute definition) {
    if(definition==null || Boolean.TRUE.equals(definition.getDeleted()) || !process.getId().equals(definition.getProductProcessId())
        || !Objects.equals(process.getRevision(),definition.getProcessRevision()) || definition.getProductName()==null || definition.getProductName().isBlank()) {
      throw invalid("Product definition does not match approved process revision");
    }
  }
  public List<Resolved> resolve(CatalogAccess.Actor actor,ApiPublication publication) {
    return publication.getSelections().stream().map(s->resolve(actor,s,publication.getCatalogType())).toList();
  }
  public void partner(String id) {
    if(!ObjectId.isValid(id)) throw invalid("Invalid Ecosystem Partner");
    var t=tenants.propositionReference(id);
    if(t.deleted() || t.partnerType()!=PartnerTypeEnum.ECOSYSTEM_PARTNER) throw invalid("Partner must be an existing active ECOSYSTEM_PARTNER Tenant");
  }
  public CatalogDtos.PartnerOption partnerOption(String id) { partner(id); var t=tenants.propositionReference(id); return new CatalogDtos.PartnerOption(t.id(),t.name()); }
  public List<String> allowedPlans(CatalogAccess.Actor actor,ApiPublication p,String partner) {
    partner(partner); return resolve(actor,p).stream().filter(s->s.partnerIds().contains(partner)).map(s->s.pin().planId()).toList();
  }
  public List<CatalogDtos.PlanOption> plans(CatalogAccess.Actor actor,CatalogTypeEnum type) {
    List<PropositionEntity> candidates=actor.tenant().enterpriseId()!=null
        ?propositions.findByEnterpriseIdAndDeletedFalse(new ObjectId(actor.tenant().enterpriseId()))
        :propositions.findByDefinitionDistributionPartnerIdsInOrDefinitionBrandIdInOrPlansTenantIdsIn(List.of(actor.tenant().id()),List.of(actor.tenant().id()),List.of(actor.tenant().id()));
    List<CatalogDtos.PlanOption> result=new ArrayList<>();
    for (var proposition : candidates) {
      addPlanOptions(actor, type, proposition, result);
    }
    return result.stream().sorted(Comparator.comparing(CatalogDtos.PlanOption::planId)).toList();
  }
  private void addPlanOptions(CatalogAccess.Actor actor, CatalogTypeEnum type, PropositionEntity proposition,
      List<CatalogDtos.PlanOption> result) {
    if (proposition.getPlans() == null) return;
    for (var plan : proposition.getPlans()) {
      try {
        var selected = resolve(actor, new CatalogDtos.Selection(proposition.getIdAsString(), plan.getIdAsString()), type);
        if (selected.partnerIds().isEmpty()) continue;
        result.add(new CatalogDtos.PlanOption(proposition.getIdAsString(), proposition.getDefinition().name(),
            plan.getIdAsString(), selected.item().name(), plan.getVersion(), selected.pin().productId(),
            selected.pin().productVersion(), planCurrency(plan),
            selected.partnerIds()));
      } catch (ResponseStatusException unavailable) { /* Only eligible, scoped options are exposed. */ }
    }
  }
  public CatalogDtos.Options options(CatalogAccess.Actor actor,CatalogTypeEnum type) {
    Set<String> ids=new TreeSet<>(); plans(actor,type).forEach(p->ids.addAll(p.eligiblePartnerIds()));
    return new CatalogDtos.Options(List.of(CatalogTypeEnum.values()),List.of("VOUCHER","COMBO"),ids.stream().map(tenants::propositionReference)
        .map(t->new CatalogDtos.PartnerOption(t.id(),t.name())).toList());
  }
  /** Plan's own costing carries a selling price and a margin-stage tax rate, not a precomputed tax amount/total — compute those here (no surcharge concept at this layer, unlike the old Plan-level MarketConfiguration). */
  private MarketConfigurationResponse price(ProductProcessEntity planProcess) {
    if(planProcess.getCostingProcess()==null || planProcess.getCostingProcess().processElementAttribute()==null
        || planProcess.getCostingProcess().processElementAttribute().size()!=1) throw invalid("Plan costing is unavailable");
    var costing=mongo.findById(planProcess.getCostingProcess().processElementAttribute().get(0),ProductCostingAttribute.class);
    if(costing==null || costing.getSellingPrice()==null || costing.getCurrency()==null) throw invalid("Plan selling price is required");
    var currency=costing.getCurrency();
    int digits;
    try { digits=Currency.getInstance(currency).getDefaultFractionDigits(); } catch(IllegalArgumentException invalid) { throw invalid("Unsupported Plan currency"); }
    if(costing.getSellingPrice().signum()<0) throw invalid("Plan selling price cannot be negative");
    var price=costing.getSellingPrice().setScale(digits,RoundingMode.HALF_UP);
    var rate=costing.getTax()==null?BigDecimal.ZERO:costing.getTax();
    var tax=price.multiply(rate).divide(new BigDecimal("100"),digits,RoundingMode.HALF_UP);
    var surcharge=BigDecimal.ZERO.setScale(digits);
    return new MarketConfigurationResponse(currency,price,rate,surcharge,tax,price.add(tax).add(surcharge));
  }
  private String planCurrency(Plan plan) {
    var process=planProcess(plan);
    if(process==null || process.getCostingProcess()==null || process.getCostingProcess().processElementAttribute()==null
        || process.getCostingProcess().processElementAttribute().isEmpty()) return null;
    var costing=mongo.findById(process.getCostingProcess().processElementAttribute().get(0),ProductCostingAttribute.class);
    return costing==null?null:costing.getCurrency();
  }
  static String fingerprint(CatalogDtos.Item item) {
    var price=item.price();
    return digest(Arrays.asList(item.propositionId(),item.planId(),Integer.toString(item.planVersion()),item.productId(),Integer.toString(item.productVersion()),
        item.code(),item.name(),item.description(),item.category(),item.imageUrl(),price==null?null:price.currency(),
        price==null?null:decimal(price.salePrice()),price==null?null:decimal(price.taxRate()),price==null?null:decimal(price.surcharge()),price==null?null:decimal(price.taxAmount()),price==null?null:decimal(price.totalPrice())));
  }
  private static String decimal(BigDecimal value) { return value==null?null:value.toPlainString(); }
  public static String hash(String text) { return digest(Collections.singletonList(text)); }
  private static String digest(List<String> values) {
    try {
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      for(String value:values) { if(value==null) { digest.update((byte)0); continue; } var bytes=value.getBytes(StandardCharsets.UTF_8); digest.update((byte)1); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes); }
      return HexFormat.of().formatHex(digest.digest());
    } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  public static ResponseStatusException invalid(String text) { return new ResponseStatusException(HttpStatus.CONFLICT,text); }
  public static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Catalog source unavailable in Tenant scope"); }
}
