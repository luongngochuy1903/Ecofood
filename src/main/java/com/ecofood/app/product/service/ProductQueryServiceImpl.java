package com.ecofood.app.product.service;
import com.ecofood.app.product.entity.*;
import com.ecofood.app.product.repository.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import com.ecofood.core.service.ProductQueryService;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
@Service @RequiredArgsConstructor
public class ProductQueryServiceImpl implements ProductQueryService {
  private final ProductRepository products;
  private final ProductProcessStore versions;
  private final ProductAuthorization authorization;
  private final ProductProcessMapper productProcessMapper;
  @Override public Page<ProductResponse> list(String search,ProductTypeEnum type,ProductStatusEnum status,Pageable pageable) {
    var actor = authorization.currentRead();
    // Phase 1 correctness-first implementation; optimize to Mongo aggregation for large catalogs.
    List<ProductEntity> candidates = actor.platform()
      ? products.findAll()
      : products.findByEnterpriseIdIn(actor.enterpriseScope().stream().map(ObjectId::new).toList());
    List<ProductResponse> filtered = candidates.stream()
      .filter(p -> !Boolean.TRUE.equals(p.getDeleted()))
      .filter(p -> type == null || p.getType()==type)
      .map(p -> versions.findByProductIdAndVersion(p.getId(),p.getLatestVersion())
             .map(v -> productProcessMapper.toResponse(p,v)).orElse(null))
      .filter(java.util.Objects::nonNull)
      .filter(p -> status == null || p.status()==status)
      .filter(p -> search==null || search.isBlank() || p.name().toLowerCase(Locale.ROOT).contains(search.trim().toLowerCase(Locale.ROOT))
          || p.code()!=null && p.code().toLowerCase(Locale.ROOT).contains(search.trim().toLowerCase(Locale.ROOT)))
      .sorted(Comparator.comparing(ProductResponse::id)).toList();
    if (pageable.isUnpaged()) return new PageImpl<>(filtered);
    long offset=pageable.getOffset();
    int from=(int)Math.min(offset,filtered.size());
    int to=(int)Math.min((long)from+pageable.getPageSize(),filtered.size());
    return new PageImpl<>(filtered.subList(from,to),pageable,filtered.size());
  }
  @Override public ProductResponse getById(String id) {
    ProductEntity p=findOwned(id);
    return productProcessMapper.toResponse(p,version(p,p.getLatestVersion()),visibleProcesses(p));
  }
  @Override public List<ProductVersionResponse> versions(String id) {
    ProductEntity p=findOwned(id);
    return visibleProcesses(p).stream().map(ProductProcessMapper::version).toList();
  }
  @Override public List<ProductProcessResponse> processes(String id) {
    ProductEntity p=findOwned(id);
    return visibleProcesses(p).stream().map(productProcessMapper::process).toList();
  }
  @Override public ProductResponse getVersion(String id,int versionNo) {
    ProductEntity p=findOwned(id);
    if(versionNo<1||versionNo>p.getLatestVersion())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product process not found");
    return productProcessMapper.toResponse(p,version(p,versionNo));
  }
  private List<ProductProcessEntity> visibleProcesses(ProductEntity p) {
    return versions.findByProductIdOrderByVersionDesc(p.getId()).stream().filter(v -> v.getVersion()<=p.getLatestVersion()).toList();
  }
  @Override public ProductResponse getApprovedVersion(String id,int versionNo) {
    ProductEntity p=findOwned(id);
    ProductProcessEntity v=version(p,versionNo);
    if (v.getStatus()!=ProductStatusEnum.APPROVED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,"Product version is not approved");
    }
    return productProcessMapper.toResponse(p,v);
  }
  private ProductEntity findOwned(String id) {
    var actor = authorization.currentRead();
    if (id == null || !ObjectId.isValid(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
    ProductEntity p=products.findById(new ObjectId(id))
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found"));
    if (Boolean.TRUE.equals(p.getDeleted())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
    if (!authorization.canRead(actor, p.getEnterpriseId()))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
    return p;
  }
  private ProductProcessEntity version(ProductEntity p,int versionNo) {
    return versions.findByProductIdAndVersion(p.getId(),versionNo)
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Product version not found"));
  }
}
