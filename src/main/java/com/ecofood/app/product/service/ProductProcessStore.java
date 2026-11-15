package com.ecofood.app.product.service;
import com.ecofood.app.product.entity.*;
import com.ecofood.app.product.repository.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Immutable attributes are staged first; the process CAS publishes all three references together. */
@Service @RequiredArgsConstructor
public class ProductProcessStore {
  private final ProductProcessRepository processes;
  private final ProductDefinitionAttributeRepository definitions;
  private final ProductCostingAttributeRepository costings;
  private final ProductOverviewAttributeRepository overviews;
  private final ProductVersionRepository legacyVersions;
  private final ProductProcessSnapshots snapshots;

  public Optional<ProductProcessEntity> findByProductIdAndVersion(ObjectId id,int version) {
    Optional<ProductProcessEntity> current=processes.findByProductIdAndVersion(id,version);
    return current.map(this::hydrate).or(() -> legacyVersions.findByProductIdAndVersion(id,version).map(this::legacy));
  }
  public List<ProductProcessEntity> findByProductIdOrderByVersionDesc(ObjectId id) {
    Map<Integer,ProductProcessEntity> result=new TreeMap<>(Comparator.reverseOrder());
    legacyVersions.findByProductIdOrderByVersionDesc(id).forEach(v -> result.put(v.getVersion(),legacy(v)));
    processes.findByProductIdOrderByVersionDesc(id).forEach(p -> result.put(p.getVersion(),hydrate(p)));
    return List.copyOf(result.values());
  }
  public ProductProcessEntity save(ProductProcessEntity p) {
    if(p.getId()==null)p.setId(new ObjectId());
    snapshots.prepare(p);
    ProductDefinitionAttribute d=p.getDefinitionAttribute();
    ProductCostingAttribute c=p.getCostingAttribute();
    ProductOverviewAttribute o=p.getOverviewAttribute();
    long nextRevision=p.getRevision()==null?0:p.getRevision()+1;
    for(ProductElementAttribute attribute:List.of(d,c,o)) {
      attribute.setId(new ObjectId());attribute.setProductProcessId(p.getId());attribute.setProcessRevision(nextRevision);
    }
    ProductDefinition oldDefinition=p.getDefinition();ProductCosting oldCosting=p.getCostingProcess();ProductOverview oldOverview=p.getOverview();
    p.setDefinition(new ProductDefinition(oldDefinition==null?new ObjectId().toHexString():oldDefinition.id(),"Definition",List.of(d.getId())));
    p.setCostingProcess(new ProductCosting(oldCosting==null?new ObjectId().toHexString():oldCosting.id(),"Costing",List.of(c.getId())));
    p.setOverview(new ProductOverview(oldOverview==null?new ObjectId().toHexString():oldOverview.id(),"Overview",List.of(o.getId())));
    List<ProductElementAttribute> inserted=new ArrayList<>();
    Long previousRevision=p.getRevision();
    boolean publishing=false;
    try {
      definitions.insert(d);inserted.add(d);costings.insert(c);inserted.add(c);overviews.insert(o);inserted.add(o);
      publishing=true;
      if(p.isLegacy()) {p.setRevision(nextRevision);processes.insert(p);p.setLegacy(false);}
      else processes.save(p);
      return p;
    } catch(RuntimeException ex) {
      // An unknown network outcome may already have published the references.
      // Only discard attributes when publication is known not to have succeeded.
      boolean safeToClean=!publishing || ex instanceof org.springframework.dao.OptimisticLockingFailureException
          || ex instanceof org.springframework.dao.DuplicateKeyException;
      for(ProductElementAttribute attribute:safeToClean?inserted:List.<ProductElementAttribute>of()) {
        try {
          if(attribute==d)definitions.deleteById(d.getId());
          else if(attribute==c)costings.deleteById(c.getId());
          else overviews.deleteById(o.getId());
        } catch(RuntimeException cleanup) {ex.addSuppressed(cleanup);}
      }
      p.setDefinition(oldDefinition);p.setCostingProcess(oldCosting);p.setOverview(oldOverview);p.setRevision(previousRevision);
      throw ex;
    }
  }
  public void deleteNewProcess(ProductProcessEntity p) {
    processes.deleteById(p.getId());
    if(p.getDefinition()!=null)p.getDefinition().processElementAttribute().forEach(definitions::deleteById);
    if(p.getCostingProcess()!=null)p.getCostingProcess().processElementAttribute().forEach(costings::deleteById);
    if(p.getOverview()!=null)p.getOverview().processElementAttribute().forEach(overviews::deleteById);
  }
  private ProductProcessEntity hydrate(ProductProcessEntity p) {
    if(p.getDefinition()==null||p.getCostingProcess()==null||p.getOverview()==null)throw missing();
    ProductDefinitionAttribute d=definitions.findById(first(p.getDefinition().processElementAttribute())).orElseThrow(this::missing);
    ProductCostingAttribute c=costings.findById(first(p.getCostingProcess().processElementAttribute())).orElseThrow(this::missing);
    ProductOverviewAttribute o=overviews.findById(first(p.getOverview().processElementAttribute())).orElseThrow(this::missing);
    for(ProductElementAttribute a:List.of(d,c,o))if(!p.getId().equals(a.getProductProcessId())||!Objects.equals(p.getRevision(),a.getProcessRevision()))throw missing();
    snapshots.project(p,d,c,o);return p;
  }
  private ObjectId first(List<ObjectId> ids) {if(ids==null||ids.size()!=1)throw missing();return ids.get(0);}
  private ResponseStatusException missing() {return new ResponseStatusException(HttpStatus.CONFLICT,"Product process attributes are missing or belong to another revision");}
  private ProductProcessEntity legacy(ProductVersionEntity v) {
    ProductProcessEntity p=new ProductProcessEntity();BeanUtils.copyProperties(v,p);p.setLegacy(true);
    snapshots.prepare(p);return p;
  }
}
