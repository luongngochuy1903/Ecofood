package com.ecofood.app.catalog;

import com.ecofood.app.product.entity.*;
import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.repository.PropositionRepository;
import com.ecofood.app.proposition.service.*;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.MarketConfigurationResponse;
import com.ecofood.core.enums.*;
import java.math.BigDecimal;
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
  public record Resolved(ApiPublication.Pin pin, CatalogDtos.Item item, List<String> partnerIds) {}
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
  public Resolved resolve(CatalogAccess.Actor actor,CatalogDtos.Selection selection,ApiPublication.CatalogType type) {
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
    var process=p.getProductProcessId()==null?null:mongo.findById(p.getProductProcessId(),ProductProcessEntity.class);
    requireApprovedSource(p,product,process);
    var definition=mongo.findById(process.getDefinition().processElementAttribute().get(0),ProductDefinitionAttribute.class);
    requireDefinition(process,definition);
    var overrides=plan.getProductOverrides();
    String name=overrides!=null && overrides.name()!=null?overrides.name():definition.getProductName();
    String description=overrides!=null && overrides.description()!=null?overrides.description():definition.getDescription();
    String image=overrides!=null && overrides.imageUrl()!=null?overrides.imageUrl():definition.getImagesURL();
    MarketConfigurationResponse price=type==ApiPublication.CatalogType.PRICE?price(plan):null;
    var item=new CatalogDtos.Item(p.getIdAsString(),plan.getIdAsString(),plan.getVersion(),product.getIdAsString(),process.getVersion(),
        definition.getCode(),name,description,definition.getCategory(),image,price);
    var pin=new ApiPublication.Pin(p.getIdAsString(),plan.getIdAsString(),plan.getVersion(),product.getIdAsString(),process.getIdAsString(),process.getVersion(),
        process.getRevision(),definition.getIdAsString(),fingerprint(item));
    return new Resolved(pin,item,eligible);
  }
  private void requireApprovedSource(PropositionEntity p,ProductEntity product,ProductProcessEntity process) {
    if(product==null || Boolean.TRUE.equals(product.getDeleted()) || !p.getEnterpriseId().equals(product.getEnterpriseId())
        || process==null || Boolean.TRUE.equals(process.getDeleted()) || !p.getProductId().equals(process.getProductId()) || process.getVersion()!=p.getProductVersion()
        || process.getStatus()!=ProductStatusEnum.APPROVED || process.getRevision()==null || process.getDefinition()==null || process.getDefinition().processElementAttribute()==null
        || process.getDefinition().processElementAttribute().size()!=1) throw invalid("Approved typed ProductProcess source is unavailable");
  }
  private void requireDefinition(ProductProcessEntity process,ProductDefinitionAttribute definition) {
    if(definition==null || Boolean.TRUE.equals(definition.getDeleted()) || !process.getId().equals(definition.getProductProcessId())
        || !Objects.equals(process.getRevision(),definition.getProcessRevision()) || definition.getProductName()==null || definition.getProductName().isBlank())
      throw invalid("Product definition does not match approved process revision");
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
  public List<CatalogDtos.PlanOption> plans(CatalogAccess.Actor actor,ApiPublication.CatalogType type) {
    List<PropositionEntity> candidates=actor.tenant().enterpriseId()!=null
        ?propositions.findByEnterpriseIdAndDeletedFalse(new ObjectId(actor.tenant().enterpriseId()))
        :propositions.findByDefinitionDistributionPartnerIdsInOrDefinitionBrandIdInOrPlansTenantIdsIn(List.of(actor.tenant().id()),List.of(actor.tenant().id()),List.of(actor.tenant().id()));
    List<CatalogDtos.PlanOption> result=new ArrayList<>();
    for(var p:candidates) if(p.getPlans()!=null) for(var plan:p.getPlans()) {
      try {
        var selected=resolve(actor,new CatalogDtos.Selection(p.getIdAsString(),plan.getIdAsString()),type);
        if(selected.partnerIds().isEmpty()) continue;
        result.add(new CatalogDtos.PlanOption(p.getIdAsString(),p.getDefinition().name(),plan.getIdAsString(),plan.getName(),plan.getVersion(),
            selected.pin().productId(),selected.pin().productVersion(),plan.getMarketConfiguration()==null?null:plan.getMarketConfiguration().currency(),selected.partnerIds()));
      } catch(ResponseStatusException unavailable) { /* Only eligible, scoped options are exposed. */ }
    }
    return result.stream().sorted(Comparator.comparing(CatalogDtos.PlanOption::planId)).toList();
  }
  public CatalogDtos.Options options(CatalogAccess.Actor actor,ApiPublication.CatalogType type) {
    Set<String> ids=new TreeSet<>(); plans(actor,type).forEach(p->ids.addAll(p.eligiblePartnerIds()));
    return new CatalogDtos.Options(List.of(ApiPublication.CatalogType.values()),List.of("VOUCHER","COMBO"),ids.stream().map(tenants::propositionReference)
        .map(t->new CatalogDtos.PartnerOption(t.id(),t.name())).toList());
  }
  private MarketConfigurationResponse price(Plan plan) {
    var m=plan.getMarketConfiguration();
    if(m==null || m.currency()==null || m.currency().isBlank() || !m.currency().equals(m.currency().toUpperCase(Locale.ROOT))) throw invalid("Approved Plan MarketConfiguration currency is missing");
    try { Currency.getInstance(m.currency()); } catch(IllegalArgumentException invalid) { throw invalid("Unsupported MarketConfiguration currency"); }
    if(java.util.Arrays.asList(m.salePrice(),m.taxRate(),m.surcharge(),m.taxAmount(),m.totalPrice()).stream().anyMatch(v->v==null || v.signum()<0)) throw invalid("MarketConfiguration price components are incomplete");
    return m; // No pricing recalculation or currency conversion in UC-017.
  }
  static String fingerprint(CatalogDtos.Item item) {
    var price=item.price();
    return digest(Arrays.asList(item.propositionId(),item.planId(),Integer.toString(item.planVersion()),item.productId(),Integer.toString(item.productVersion()),
        item.code(),item.name(),item.description(),item.category(),item.imageUrl(),price==null?null:price.currency(),
        price==null?null:decimal(price.salePrice()),price==null?null:decimal(price.taxRate()),price==null?null:decimal(price.surcharge()),price==null?null:decimal(price.taxAmount()),price==null?null:decimal(price.totalPrice())));
  }
  private static String decimal(BigDecimal value) { return value==null?null:value.toPlainString(); }
  static String hash(String text) { return digest(Collections.singletonList(text)); }
  private static String digest(List<String> values) {
    try {
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      for(String value:values) { if(value==null) { digest.update((byte)0); continue; } var bytes=value.getBytes(StandardCharsets.UTF_8); digest.update((byte)1); digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes); }
      return HexFormat.of().formatHex(digest.digest());
    } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  static ResponseStatusException invalid(String text) { return new ResponseStatusException(HttpStatus.CONFLICT,text); }
  static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Catalog source unavailable in Tenant scope"); }
}
