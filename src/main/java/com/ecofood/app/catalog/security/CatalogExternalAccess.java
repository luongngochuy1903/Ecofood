package com.ecofood.app.catalog.security;

import static com.ecofood.core.dto.CatalogDtos.*;

import com.ecofood.app.catalog.entity.ApiPublication;
import com.ecofood.app.catalog.service.CatalogData;
import com.ecofood.app.catalog.service.CatalogPublicationServiceImpl;
import com.ecofood.core.enums.CatalogPublicationStatusEnum;
import com.ecofood.core.service.CatalogExternalService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CatalogExternalAccess implements CatalogExternalService {
  private static final String GRANTS_PREFIX = "grants.";
  private final MongoTemplate mongo;
  private final CatalogAccess access;
  private final CatalogData data;
  @Override
  public ExternalPrincipal authenticate(String header) {
    if(header==null || header.length()>160 || !header.regionMatches(true,0,"Catalog ",0,8)) throw unauthorized();
    String token=header.substring(8);
    if(!token.matches("[0-9a-f]{64}\\.[0-9a-f]{32}\\.[A-Za-z0-9_-]{43}")) throw unauthorized();
    String[] parts=token.split("\\."); var p=find(parts[0]); if(p==null) throw unauthorized();
    var grant=p.getGrants().values().stream().filter(g->g.id().equals(parts[1])).findFirst().orElseThrow(CatalogExternalAccess::unauthorized);
    if(!MessageDigest.isEqual(CatalogData.hash(token).getBytes(StandardCharsets.UTF_8),grant.credentialHash().getBytes(StandardCharsets.UTF_8))) throw unauthorized();
    var principal=new ExternalPrincipal(p.getId(),grant.partnerId(),grant.id()); active(p,principal); return principal;
  }
  private ApiPublication find(String id) {
    return mongo.findOne(Query.query(Criteria.where("_id").is(id)).withReadPreference(com.mongodb.ReadPreference.primary()),ApiPublication.class);
  }
  private ApiPublication.PartnerAccess active(ApiPublication p,ExternalPrincipal principal) {
    var grant=p==null?null:p.getGrants().get(principal.partnerId()); Instant now=CatalogPublicationServiceImpl.now();
    if(grant==null || !grant.id().equals(principal.grantId())) throw unauthorized();
    if(grant.revokedAt()!=null) throw denied(p,principal,"PARTNER_REVOKED",HttpStatus.FORBIDDEN);
    if(!now.isBefore(grant.expiresAt())) throw denied(p,principal,"CREDENTIAL_EXPIRED",HttpStatus.UNAUTHORIZED);
    if(p.getStatus()!=CatalogPublicationStatusEnum.PUBLISHED || p.getPolicy()==null || now.isBefore(p.getPolicy().startsAt()) || !now.isBefore(p.getPolicy().expiresAt()))
      throw denied(p,principal,"PUBLICATION_NOT_ACTIVE",HttpStatus.FORBIDDEN);
    try { data.partner(principal.partnerId()); access.publisher(p.getPublishedBy(),p.getTenantId()); }
    catch(ResponseStatusException unavailable) { throw denied(p,principal,"PUBLISHER_OR_PARTNER_SCOPE_REVOKED",HttpStatus.FORBIDDEN); }
    return grant;
  }
  @Override
  public Session session(ExternalPrincipal principal) {
    var p=find(principal.publicationId()); var grant=active(p,principal);
    return new Session(p.getId(),grant.partnerId(),p.getApiKey(),p.getVersion(),grant.expiresAt());
  }
  @Override
  public Catalog read(ExternalPrincipal principal,String id,int version,String planId,String search,int page,int size) {
    CatalogPublicationServiceImpl.page(page,size);
    if(!principal.publicationId().equals(id)) throw CatalogData.unavailable();
    var p=find(id); var grant=active(p,principal);
    if(p.getVersion()!=version) throw CatalogData.unavailable();
    if(size>p.getPolicy().maxPageSize()) throw CatalogPublicationServiceImpl.bad("Page size exceeds publication policy");
    if(search!=null && search.length()>200) throw CatalogPublicationServiceImpl.bad("Search is too long");
    if(planId!=null && !grant.planIds().contains(planId)) throw CatalogData.unavailable();
    var actor=access.publisher(p.getPublishedBy(),p.getTenantId());
    List<Item> permitted=new ArrayList<>();
    for(var pin:p.getPins()) {
      if(!grant.planIds().contains(pin.planId())) continue;
      CatalogData.Resolved source;
      try {
        source=data.resolve(actor,new Selection(pin.propositionId(),pin.planId()),p.getCatalogType());
        if(!pin.equals(source.pin())) throw CatalogData.invalid("Published source revision changed; a new publication version is required");
      } catch(ResponseStatusException unavailable) { recordDenied(p,principal,"SOURCE_NOT_PUBLISHABLE"); throw unavailable; }
      if(!source.partnerIds().contains(principal.partnerId())) throw denied(p,principal,"PARTNER_DISTRIBUTION_REVOKED",HttpStatus.FORBIDDEN);
      if(planId==null || planId.equals(pin.planId())) permitted.add(source.item());
    }
    if(permitted.isEmpty()) throw denied(p,principal,"EMPTY_PARTNER_SCOPE",HttpStatus.FORBIDDEN);
    String needle=search==null?"":search.trim().toLowerCase(Locale.ROOT);
    var rows=permitted.stream().filter(item->needle.isEmpty() || Objects.toString(item.name(),"").toLowerCase(Locale.ROOT).contains(needle) || Objects.toString(item.code(),"").toLowerCase(Locale.ROOT).contains(needle))
        .sorted(Comparator.comparing(Item::planId)).toList();
    int from=(int)Math.min((long)page*size,rows.size()),to=Math.min(from+size,rows.size()); Instant fence=CatalogPublicationServiceImpl.now();
    // Authorization fence: a completed revoke/rotation/retire wins over a read which was still resolving source data.
    var query=Query.query(Criteria.where("_id").is(p.getId()).and("status").is(CatalogPublicationStatusEnum.PUBLISHED)
        .and(GRANTS_PREFIX+principal.partnerId()+".id").is(principal.grantId()).and(GRANTS_PREFIX+principal.partnerId()+".revokedAt").is(null)
        .and(GRANTS_PREFIX+principal.partnerId()+".expiresAt").gt(fence).and("policy.startsAt").lte(fence).and("policy.expiresAt").gt(fence));
    if(mongo.updateFirst(query,new Update().inc("accessCount",1).set("lastAccessAt",fence).set("lastAccessPartnerId",principal.partnerId()),ApiPublication.class).getModifiedCount()!=1)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Catalog access changed; request denied");
    return new Catalog(p.getApiKey(),p.getVersion(),p.getContractVersion(),p.getCatalogType(),List.copyOf(rows.subList(from,to)),page,size,to<rows.size());
  }
  private ResponseStatusException denied(ApiPublication p,ExternalPrincipal principal,String code,HttpStatus status) {
    recordDenied(p,principal,code);
    return new ResponseStatusException(status,code);
  }
  private void recordDenied(ApiPublication p,ExternalPrincipal principal,String code) {
    try { mongo.updateFirst(Query.query(Criteria.where("_id").is(p.getId()).and(GRANTS_PREFIX+principal.partnerId()+".id").is(principal.grantId())),
        new Update().inc("deniedCount",1).set("lastDeniedAt",CatalogPublicationServiceImpl.now()).set("lastDeniedCode",code).set("lastDeniedPartnerId",principal.partnerId()),ApiPublication.class); }
    catch(RuntimeException unavailable) { /* Deny remains fail-closed even if audit counters are unavailable. */ }
  }
  public static ResponseStatusException unauthorized() { return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Valid external Catalog credential required"); }
}
