package com.ecofood.app.catalog.service;

import static com.ecofood.core.dto.CatalogDtos.*;

import com.ecofood.app.catalog.entity.ApiPublication;
import com.ecofood.app.catalog.security.CatalogAccess;
import com.ecofood.core.dto.CatalogDtos;
import com.ecofood.core.dto.CatalogOrchestrator;
import com.ecofood.core.enums.CatalogPublicationStatusEnum;
import com.ecofood.core.enums.CatalogTypeEnum;
import com.ecofood.core.service.CatalogPublicationService;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CatalogPublicationServiceImpl implements CatalogPublicationService {
  private static final String STATUS = "status";
  private static final String VALIDATED_AT = "validatedAt";
  private static final String SUCCESS = "SUCCESS";
  private static final SecureRandom RANDOM=new SecureRandom();
  private final MongoTemplate mongo;
  private final CatalogAccess access;
  private final CatalogData data;
  @Override
  public Capabilities capabilities() {
    var a=access.current(); return new Capabilities(a.tenant().id(),true,a.permissions().contains(CatalogAccess.MANAGE),a.permissions().contains(CatalogAccess.PUBLISH),a.permissions().contains(CatalogAccess.GRANT));
  }
  @Override
  public Options options(CatalogTypeEnum type) { return data.options(access.current(),type); }
  @Override
  public Page<PlanOption> plans(CatalogTypeEnum type,String search,int page,int size) {
page(page,size);
    var options=data.plans(access.current(),type);
    String needle=search==null?"":search.trim().toLowerCase(Locale.ROOT);
    var filtered=options.stream().filter(p->needle.isEmpty() || Objects.toString(p.planName(),"").toLowerCase(Locale.ROOT).contains(needle) || Objects.toString(p.propositionName(),"").toLowerCase(Locale.ROOT).contains(needle)).toList();
    return slice(filtered,page,size);
  }
  @Override
  public Page<View> list(CatalogTypeEnum type,String apiKey,int page,int size) {
    var a=access.current(); page(page,size); var q=Query.query(Criteria.where("tenantId").is(a.tenant().id()));
    if(type!=null) q.addCriteria(Criteria.where("catalogType").is(type)); if(apiKey!=null && !apiKey.isBlank()) q.addCriteria(Criteria.where("apiKey").is(apiKey));
    // Drafts have no pins yet; authorize their selections as well, before pagination.
    var rows=mongo.find(q.with(Sort.by(Sort.Direction.DESC,"createdAt","id")),ApiPublication.class);
    var visible=new ArrayList<View>(); for(var p:rows) { try { authorize(a,p); visible.add(view(p)); } catch(ResponseStatusException denied) { /* Do not disclose inaccessible source selections. */ } }
    return slice(visible,page,size);
  }
  @Override
  public View detail(String id) { var a=access.current(); return view(load(a,id)); }
  @Override
  public List<CatalogDtos.Audit> audit(String id) { var a=access.current(); return load(a,id).getAudit(); }
  @Override
  public View save(String id,Draft input) {
    var a=access.current(); a.require(CatalogAccess.MANAGE); validateDraft(input); data.authorize(a,input.selections());
    for(String partner:input.partnerIds()) data.partner(partner);
    if(id==null) {
      var p=new ApiPublication(); p.setId(CatalogData.hash(a.tenant().id()+"\n"+input.catalogType()+"\n"+input.apiKey()+"\n"+input.version()));
      p.setTenantId(a.tenant().id()); p.setApiKey(input.apiKey()); p.setName(input.name().trim()); p.setCatalogType(input.catalogType()); p.setVersion(input.version()); p.setRevision(1); p.setStatus(CatalogPublicationStatusEnum.DRAFT);
      p.setSelections(List.copyOf(input.selections())); p.setPartnerIds(List.copyOf(input.partnerIds())); p.setPolicy(input.policy());
      p.setCatalogOrchestrator(new CatalogOrchestrator(UUID.randomUUID().toString(),custom(input),input.selections().stream().map(Selection::planId).toList()));
      p.setCreatedAt(now()); p.setUpdatedAt(p.getCreatedAt()); p.setCreatedBy(a.userId()); p.setUpdatedBy(a.userId()); p.setPins(List.of());
      p.setAudit(List.of(event(p,a,"CREATE_DRAFT",SUCCESS,"Draft created")));
      try { mongo.insert(p); } catch(DuplicateKeyException duplicate) { throw new ResponseStatusException(HttpStatus.CONFLICT,"API/type/version already exists in this Tenant"); }
      return view(p);
    }
    var p=load(a,id);
    if(p.getStatus()!=CatalogPublicationStatusEnum.DRAFT && p.getStatus()!=CatalogPublicationStatusEnum.VALIDATED) throw conflict();
    if(input.revision()==null || input.revision()!=p.getRevision()) throw conflict();
    if(!p.getApiKey().equals(input.apiKey()) || p.getVersion()!=input.version() || p.getCatalogType()!=input.catalogType()) throw bad("API key, catalog type and version are immutable; create a new version");
    return view(write(p,a,new Update().set("name",input.name().trim()).set("selections",List.copyOf(input.selections())).set("partnerIds",List.copyOf(input.partnerIds())).set("policy",input.policy())
        .set("catalogOrchestrator",new CatalogOrchestrator(p.getCatalogOrchestrator().id(),custom(input),input.selections().stream().map(Selection::planId).toList()))
        .set(STATUS,CatalogPublicationStatusEnum.DRAFT).set("pins",List.of()).unset(VALIDATED_AT),"UPDATE_DRAFT",SUCCESS,"Draft updated; validation invalidated"));
  }
  @Override
  public View validate(String id,Revision input) {
    var a=access.current(); a.require(CatalogAccess.PUBLISH); var p=load(a,id); revision(p,input);
    if(p.getStatus()!=CatalogPublicationStatusEnum.DRAFT && p.getStatus()!=CatalogPublicationStatusEnum.VALIDATED) throw conflict();
    try {
      validatePolicy(p.getPolicy()); var sources=data.resolve(a,p);
      for(String partner:p.getPartnerIds()) {
        data.partner(partner); if(sources.stream().noneMatch(s->s.partnerIds().contains(partner))) throw bad("Partner has no distributable Plan in selected scope");
      }
      return view(write(p,a,new Update().set(STATUS,CatalogPublicationStatusEnum.VALIDATED).set("pins",sources.stream().map(CatalogData.Resolved::pin).toList()).set(VALIDATED_AT,now()),"VALIDATE",SUCCESS,"Sources and partner scopes validated"));
    } catch(ResponseStatusException failure) {
      // Persist a safe validation outcome without recording request content or credentials.
      try { write(p,a,new Update().set(STATUS,CatalogPublicationStatusEnum.DRAFT).set("pins",List.of()).unset(VALIDATED_AT),"VALIDATE","FAILED","Catalog source/policy validation failed"); } catch(ResponseStatusException concurrent) { /* Winning revision remains authoritative. */ }
      throw failure;
    }
  }
  @Override
  public View publish(String id,Revision input) {
    var a=access.current(); a.require(CatalogAccess.PUBLISH); var p=load(a,id); revision(p,input);
    if(p.getStatus()!=CatalogPublicationStatusEnum.VALIDATED) throw conflict(); validatePolicy(p.getPolicy());
    var current=data.resolve(a,p).stream().map(CatalogData.Resolved::pin).toList();
    if(!Objects.equals(p.getPins(),current)) throw CatalogData.invalid("Source changed since validation; validate again");
    for(String partner:p.getPartnerIds()) if(data.allowedPlans(a,p,partner).isEmpty()) throw CatalogData.invalid("Partner distribution changed; validate again");
    String endpoint="/api/external/v1/catalog/"+p.getId()+"/versions/"+p.getVersion();
    var updated=write(p,a,new Update().set(STATUS,CatalogPublicationStatusEnum.PUBLISHED).set("endpoint",endpoint).set("publishedAt",now()).set("publishedBy",a.userId()),
        "PUBLISH",SUCCESS,"Published contract v1; sources are pinned, eligible sources required on every read");
    return view(updated);
  }
  @Override
  public View retire(String id,Revision input) {
    var a=access.current(); a.require(CatalogAccess.PUBLISH); var p=load(a,id); revision(p,input);
    if(p.getStatus()!=CatalogPublicationStatusEnum.PUBLISHED) throw conflict();
    return view(write(p,a,new Update().set(STATUS,CatalogPublicationStatusEnum.RETIRED).set("retiredAt",now()),"RETIRE",SUCCESS,"New external requests disabled; historical configuration retained"));
  }
  @Override
  public Credential grant(String id,Grant input) {
    var a=access.current(); a.require(CatalogAccess.GRANT); var p=load(a,id);
    if(input==null || input.revision()!=p.getRevision() || p.getStatus()!=CatalogPublicationStatusEnum.PUBLISHED) throw conflict();
    validatePolicy(p.getPolicy());
    if(input.expiresAt()==null || !input.expiresAt().isAfter(now()) || !input.expiresAt().isAfter(p.getPolicy().startsAt()) || input.expiresAt().isAfter(p.getPolicy().expiresAt())) throw bad("Credential expiry must overlap and remain within publication validity");
    var sources=data.resolve(a,p); if(!sources.stream().map(CatalogData.Resolved::pin).toList().equals(p.getPins())) throw CatalogData.invalid("Published source changed; create a new publication version");
    var permitted=data.allowedPlans(a,p,input.partnerId());
    List<String> selected=input.planIds()==null?permitted:input.planIds();
    if(selected.isEmpty() || selected.size()>100 || new HashSet<>(selected).size()!=selected.size() || !permitted.containsAll(selected)) throw bad("Partner scope must be an eligible subset of published Plans");
    if(p.getGrants().size()>=50 && !p.getGrants().containsKey(input.partnerId())) throw bad("Partner access limit reached");
    String grantId=UUID.randomUUID().toString().replace("-",""); byte[] secret=new byte[32]; RANDOM.nextBytes(secret);
    String raw=p.getId()+"."+grantId+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    var grant=new ApiPublication.PartnerAccess(grantId,input.partnerId(),CatalogData.hash(raw),List.copyOf(selected),input.expiresAt(),now(),a.userId(),null);
    var partnerIds=new ArrayList<>(p.getPartnerIds()); if(!partnerIds.contains(input.partnerId())) partnerIds.add(input.partnerId());
    write(p,a,new Update().set("grants."+input.partnerId(),grant).set("partnerIds",partnerIds),"GRANT_PARTNER_ACCESS",SUCCESS,"Partner "+input.partnerId()+"; credential "+grantId+"; plans "+String.join(",",selected)+"; previous credential replaced");
    return new Credential(p.getId(),input.partnerId(),"Catalog "+raw,grant.expiresAt(),p.getEndpoint());
  }
  @Override
  public List<PartnerScope> partnerOptions(String id) {
    var a=access.current(); a.require(CatalogAccess.GRANT); var p=load(a,id);
    if(p.getStatus()!=CatalogPublicationStatusEnum.PUBLISHED) throw conflict();
    var sources=data.resolve(a,p); if(!sources.stream().map(CatalogData.Resolved::pin).toList().equals(p.getPins())) throw CatalogData.invalid("Published source changed; create a new publication version");
    Set<String> ids=new TreeSet<>(); sources.forEach(s->ids.addAll(s.partnerIds()));
    return ids.stream().map(partner->{ var target=data.partnerOption(partner); return new PartnerScope(target.id(),target.name(),sources.stream().filter(s->s.partnerIds().contains(partner)).map(CatalogData.Resolved::item).toList()); }).toList();
  }
  @Override
  public View revoke(String id,String partnerId,Revision input) {
    var a=access.current(); a.require(CatalogAccess.GRANT); var p=load(a,id); revision(p,input);
    if(!org.bson.types.ObjectId.isValid(partnerId)) throw bad("Invalid Partner ID");
    var grant=p.getGrants().get(partnerId); if(grant==null) throw CatalogData.unavailable();
    if(grant.revokedAt()!=null) return view(p);
    return view(write(p,a,new Update().set("grants."+partnerId+".revokedAt",now()),"REVOKE_PARTNER_ACCESS",SUCCESS,"Partner "+partnerId+"; credential "+grant.id()+" revoked"));
  }
  private ApiPublication load(CatalogAccess.Actor a,String id) {
    var p=mongo.findOne(Query.query(Criteria.where("_id").is(id).and("tenantId").is(a.tenant().id())),ApiPublication.class);
    if(p==null) throw CatalogData.unavailable(); authorize(a,p); return p;
  }
  private void authorize(CatalogAccess.Actor a,ApiPublication p) {
    if(!a.tenant().id().equals(p.getTenantId())) throw CatalogData.unavailable();
    // Historical publications remain inspectable/retirable even after a source expires or is withdrawn.
    if(p.getPins()!=null && !p.getPins().isEmpty()) p.getPins().forEach(pin->a.product(pin.productId()));
    else if(a.productIds()!=null) data.authorize(a,p.getSelections());
  }
  private ApiPublication write(ApiPublication p,CatalogAccess.Actor a,Update update,String action,String result,String detail) {
    if(p.getAudit().size()>=2000 && !Set.of("RETIRE","REVOKE_PARTNER_ACCESS").contains(action)) throw bad("Publication audit limit reached; retire and create a new version");
    if("PUBLISH".equals(action) && p.getVersion()>1) update.push("audit").each(event(p,a,action,result,detail),event(p,a,"PUBLISH_NEW_VERSION",SUCCESS,"Published parallel API version "+p.getVersion()));
    else update.push("audit",event(p,a,action,result,detail));
    var updated=mongo.findAndModify(Query.query(Criteria.where("_id").is(p.getId()).and("tenantId").is(a.tenant().id()).and("revision").is(p.getRevision()).and(STATUS).is(p.getStatus())),
        update.inc("revision",1).set("updatedAt",now()).set("updatedBy",a.userId()),FindAndModifyOptions.options().returnNew(true),ApiPublication.class);
    if(updated==null) throw conflict(); return updated;
  }
  private CatalogDtos.Audit event(ApiPublication p,CatalogAccess.Actor a,String action,String result,String detail) {
    return new CatalogDtos.Audit(UUID.randomUUID().toString(),a.userId(),p.getTenantId(),p.getApiKey(),p.getVersion(),now(),action,result,detail);
  }
  static View view(ApiPublication p) {
    Instant now=now();
    return new View(p.getId(),p.getTenantId(),p.getApiKey(),p.getName(),p.getCatalogType(),p.getVersion(),p.getContractVersion(),p.getRevision(),p.getStatus(),p.getCatalogOrchestrator(),
        p.getSelections(),p.getPins(),p.getPartnerIds(),p.getPolicy(),p.getEndpoint(),p.getCreatedAt(),p.getUpdatedAt(),p.getValidatedAt(),p.getPublishedAt(),p.getRetiredAt(),
        p.getGrants().values().stream().map(g->new GrantView(g.id(),g.partnerId(),g.planIds(),g.expiresAt(),g.issuedAt(),g.issuedBy(),g.revokedAt(),
          p.getStatus()==CatalogPublicationStatusEnum.PUBLISHED && g.revokedAt()==null && now.isBefore(g.expiresAt()) && !now.isBefore(p.getPolicy().startsAt()) && now.isBefore(p.getPolicy().expiresAt()))).toList(),
        p.getAudit(),p.getAccessCount(),p.getLastAccessAt(),p.getLastAccessPartnerId(),p.getDeniedCount(),p.getLastDeniedAt(),p.getLastDeniedCode(),p.getLastDeniedPartnerId());
  }
  private void validateDraft(Draft input) {
    if(input==null || input.apiKey()==null || !input.apiKey().matches("[a-z][a-z0-9-]{2,59}") || input.name()==null || input.name().isBlank() || input.name().length()>150 || input.catalogType()==null || input.version()<1 || input.version()>10000
        || input.selections()==null || input.selections().isEmpty() || input.selections().size()>100 || input.selections().stream().anyMatch(Objects::isNull)
        || new HashSet<>(input.selections()).size()!=input.selections().size() || input.selections().stream().map(Selection::planId).distinct().count()!=input.selections().size()
        || input.partnerIds()==null || input.partnerIds().isEmpty() || input.partnerIds().size()>50 || new HashSet<>(input.partnerIds()).size()!=input.partnerIds().size()) throw bad("Invalid publication identity or scope");
    validatePolicy(input.policy()); var fields=custom(input);
    if(fields.size()>10 || fields.stream().anyMatch(f->f==null || !Set.of("label","description").contains(f.key()==null?"":f.key()) || f.value()==null || f.value().length()>500)
        || fields.stream().map(CatalogOrchestrator.CustomField::key).distinct().count()!=fields.size()) throw bad("Only label/description display metadata is supported; no dynamic field mapping");
  }
  private List<CatalogOrchestrator.CustomField> custom(Draft draft) {
    if(draft.customField()==null) return List.of();
    if(draft.customField().stream().anyMatch(Objects::isNull)) throw bad("Invalid custom display metadata");
    return List.copyOf(draft.customField());
  }
  static void validatePolicy(Policy policy) {
    if(policy==null || policy.startsAt()==null || policy.expiresAt()==null || !policy.expiresAt().isAfter(policy.startsAt()) || !policy.expiresAt().isAfter(now()) || policy.maxPageSize()<1 || policy.maxPageSize()>100) throw bad("Invalid validity window or page limit");
  }
  private void revision(ApiPublication p,Revision request) { if(request==null || request.revision()!=p.getRevision()) throw conflict(); }
  public static void page(int page,int size) { if(page<0 || page>100000 || size<1 || size>100) throw bad("Invalid pagination"); }
  private static <T> Page<T> slice(List<T> rows,int page,int size) { int from=(int)Math.min((long)page*size,rows.size()),to=Math.min(from+size,rows.size()); return new Page<>(rows.subList(from,to),page,size,to<rows.size()); }
  public static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
  static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Publication changed or lifecycle action is not allowed; reload"); }
  public static ResponseStatusException bad(String text) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,text); }
}
