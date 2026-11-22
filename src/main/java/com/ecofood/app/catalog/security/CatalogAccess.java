package com.ecofood.app.catalog.security;

import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.userACL.entity.*;
import com.ecofood.app.userACL.entity.Group;
import com.ecofood.app.userACL.entity.GroupEntity;
import com.ecofood.app.userACL.entity.UserGroupEntity;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.PropositionTenantReference;
import com.ecofood.core.enums.*;
import com.ecofood.core.enums.AccessEntityTypeEnum;
import com.ecofood.core.enums.AccessLevelEnum;
import com.ecofood.core.enums.GroupType;
import com.ecofood.core.enums.PartnerTypeEnum;
import com.ecofood.core.enums.TenantTypeEnum;
import com.ecofood.core.security.AuthenticationContext;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CatalogAccess {
  public static final String READ="CATALOG_API_READ", MANAGE="CATALOG_API_MANAGE", PUBLISH="CATALOG_API_PUBLISH", GRANT="CATALOG_API_GRANT";
  private final NotificationDirectory directory;
  private final CoreTenantQueryClient tenants;
  private final MongoTemplate mongo;
  public record Actor(String userId, PropositionTenantReference tenant, Set<String> productIds, Set<String> permissions) {
    public void require(String permission) { if(!permissions.contains(permission)) throw denied(); }
    public void product(String id) { if(productIds!=null && !productIds.contains(id)) throw denied(); }
  }
  public Actor current() {
    try {
      String id=AuthenticationContext.getUserId().toHexString();
      String tenant=AuthenticationContext.getTenantId().toHexString();
      return resolve(id,tenant,Optional.ofNullable(AuthenticationContext.getRoles()).orElse(List.of()),Optional.ofNullable(AuthenticationContext.getPermissions()).orElse(List.of()));
    } catch(ResponseStatusException e) { throw e; } catch(RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Internal Employee authentication required"); }
  }
  /** External requests recheck the live authority which published this scope; no employee JWT is reused externally. */
  public Actor publisher(String id,String tenant) {
    var actor=resolve(id,tenant,null,null); actor.require(PUBLISH); return actor;
  }
  private Actor resolve(String id,String tenant,List<String> tokenRoles,List<String> tokenPermissions) {
    var user=directory.user(id,tenant);
    if(user==null || user.getUserCategory()==null || !"PARTNER".equals(user.getUserCategory().getUserCategoryCode())) throw denied();
    var reference=tenants.propositionReference(tenant); if(reference.deleted() || reference.partnerType()==PartnerTypeEnum.ECO) throw denied();
    var live=directory.claims(user,tenant); Set<String> roles=new HashSet<>(live.roles()), permissions=new HashSet<>(live.permissions());
    if(tokenRoles!=null) roles.retainAll(tokenRoles); if(tokenPermissions!=null) permissions.retainAll(tokenPermissions);
    String prefix=reference.partnerType()==PartnerTypeEnum.ENTERPRISE || reference.partnerType()==PartnerTypeEnum.REGIONAL_ENTERPRISE ? "ENTERPRISE" : reference.tenantType()==TenantTypeEnum.MASTER_TENANT ? "MASTER" : "TENANT";
    if(List.of(prefix+"_ADMIN",prefix+"_PRODUCT_MANAGER",prefix+"_EMPLOYEE").stream().noneMatch(roles::contains)) throw denied();
    permissions.add(READ);
    if(roles.contains(prefix+"_ADMIN")) permissions.addAll(Set.of(MANAGE,PUBLISH,GRANT));
    return new Actor(id,reference,productScope(id,tenant),Set.copyOf(permissions));
  }
  private Set<String> productScope(String id,String tenant) {
    var membership=mongo.findOne(Query.query(Criteria.where("userIdentifier").is(new ObjectId(id)).and("deleted").ne(true)),UserGroupEntity.class);
    if(membership==null || membership.getGroups()==null) return null;
    boolean restricted=false,full=false; Set<String> ids=new HashSet<>();
    for(var link:membership.getGroups()) {
      if(!new ObjectId(tenant).equals(link.getTenantIdentifier()) || link.getGroupIdentifier()==null) continue;
      var group=mongo.findById(link.getGroupIdentifier(),Group.class);
      if(group==null || Boolean.TRUE.equals(group.getDeleted()) || group.getGroupType()!=GroupType.ACCESS_CONTROL_GROUP || group.getAccessEntityType()!=AccessEntityTypeEnum.Product
          || group.getPartyRelationships()==null || group.getPartyRelationships().stream().noneMatch(r->"TENANT".equals(r.getRelationshipTypeCode()) && new ObjectId(tenant).equals(r.getToPartyId()))) continue;
      restricted=true; full|=group.getAccessLevel()==AccessLevelEnum.FULL_ACCESS;
      if(group.getAccessLevel()==AccessLevelEnum.LIMITED_ACCESS) for(var entities:mongo.find(Query.query(Criteria.where("groupIdentifier").is(group.getId()).and("accessEntityType").is(AccessEntityTypeEnum.Product).and("deleted").ne(true)),GroupEntity.class))
        if(entities.getEntityIds()!=null) entities.getEntityIds().forEach(value->ids.add(value.toHexString()));
    }
    return !restricted || full?null:Set.copyOf(ids);
  }
  static ResponseStatusException denied() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"Catalog Employee permission or data scope denied"); }
}
