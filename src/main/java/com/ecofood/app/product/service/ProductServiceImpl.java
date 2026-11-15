package com.ecofood.app.product.service;
import com.ecofood.app.product.entity.*;
import com.ecofood.app.product.repository.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ProductService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
@Service @RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
  private final ProductRepository productRepository;
  private final ProductProcessStore versionRepository;
  private final ProductAuthorization authorization;
  private final ProductValidation validation;
  private final ProductAuditService audit;

  @Override public ProductResponse create(ProductCreateRequest request) {
    ObjectId enterprise = authorization.requireEnterprise();
    if (request == null || request.type() == null) bad("Product type is required");
    validation.validateDraft(request.name(),request.ingredients(),request.recipe(),request.pricing());
    validation.validateDefinition(request.code(),request.category(),request.imageUrl());
    String code = request.code().trim().toUpperCase(Locale.ROOT);
    requireAvailableCode(enterprise, code, null);
    ObjectId source = null;
    if (request.type() == ProductTypeEnum.ADAPTED) {
      if (request.sourceProductId() == null || !ObjectId.isValid(request.sourceProductId())) bad("Adapted product requires a valid sourceProductId");
      source = new ObjectId(request.sourceProductId());
      ProductEntity parent = findOwned(source, enterprise);
      requireMaster(parent);
      validateDependencyGraph(parent,enterprise,new HashSet<>(),new HashSet<>());
    } else if (request.sourceProductId() != null && !request.sourceProductId().isBlank()) {
      bad("sourceProductId is allowed only for ADAPTED products");
    }
    validateComponents(request.type(), request.componentProducts(), enterprise, null);
    ProductEntity product = ProductEntity.builder().enterpriseId(enterprise).type(request.type())
      .sourceProductId(source).code(code).latestVersion(1).latestStatus(ProductStatusEnum.DRAFT).build();
    // Assign the ObjectId up-front: version and parent share a stable id before either save.
    product.setId(new ObjectId());
    ProductProcessEntity version = ProductProcessEntity.builder().productId(product.getId()).version(1)
      .status(ProductStatusEnum.DRAFT).name(request.name().trim()).description(request.description())
      .code(code).category(request.category().trim()).imageUrl(request.imageUrl())
      .componentProducts(components(request.componentProducts()))
      .ingredients(copy(request.ingredients())).recipe(request.recipe()).pricing(fixedPricing(request.pricing())).costing(request.costing()).build();
    version.setId(new ObjectId());
    product.setDescription(version.getDescription());product.setCategory(List.of(version.getCategory()));
    product.setProductStatus(version.getStatus());product.setProductProcess(List.of(version.getId()));
    saveProduct(product);
    try { versionRepository.save(version); }
    catch (RuntimeException ex) { productRepository.deleteById(product.getId()); throw ex; }
    audit.record(product,version,"CREATE","Draft created");
    return ProductProcessMapper.toResponse(product,version);
  }

  @Override public ProductResponse update(String id, ProductUpdateRequest request) {
    ProductEntity product = findOwned(id);
    ProductProcessEntity v = latest(product);
    if (v.getStatus()!=ProductStatusEnum.DRAFT && v.getStatus()!=ProductStatusEnum.REJECTED)
      conflict("Only DRAFT or REJECTED version may be edited");
    if (request.expectedRevision() == null) bad("expectedRevision is required");
    if (!request.expectedRevision().equals(v.getRevision())) conflict("Version was modified; reload before saving");
    validation.validateDraft(request.name(),request.ingredients(),request.recipe(),request.pricing());
    validation.validateDefinition(request.code(),request.category(),request.imageUrl());
    String code = request.code().trim().toUpperCase(Locale.ROOT);
    if (product.getCode() != null && !code.equals(product.getCode())) bad("Product code cannot be changed after creation");
    requireAvailableCode(product.getEnterpriseId(), code, product.getId());
    validateComponents(product.getType(),request.componentProducts(),product.getEnterpriseId(),product.getId());
    validateSource(product);
    if (product.getCode() == null) { product.setCode(code); saveProduct(product); }
    v.setCode(code); v.setCategory(request.category().trim()); v.setImageUrl(request.imageUrl());
    v.setComponentProducts(components(request.componentProducts()));
    v.setName(request.name().trim()); v.setDescription(request.description());
    v.setIngredients(copy(request.ingredients())); v.setRecipe(request.recipe()); v.setPricing(fixedPricing(request.pricing()));v.setCosting(request.costing());
    if (v.getStatus()==ProductStatusEnum.REJECTED) {
      v.setStatus(ProductStatusEnum.DRAFT); v.setRejectionReason(null);
    }
    saveVersion(v);
    syncProductSummary(product,v);
    audit.record(product,v,"UPDATE","Draft updated");
    return ProductProcessMapper.toResponse(product,v);
  }

  @Override public ProductResponse submit(String id) {
    ProductEntity product = findOwned(id);
    ProductProcessEntity v = latest(product);
    if (v.getStatus()!=ProductStatusEnum.DRAFT) conflict("Only DRAFT product can be submitted");
    validation.validateSubmit(v);
    requireAvailableCode(product.getEnterpriseId(), v.getCode(), product.getId());
    validateSource(product);
    validateComponents(product.getType(),v.getComponentProducts(),product.getEnterpriseId(),product.getId());
    v.setStatus(ProductStatusEnum.PENDING_REVIEW);
    saveVersion(v); syncProductSummary(product,v);
    audit.record(product,v,"SUBMIT","Submitted for review");
    return ProductProcessMapper.toResponse(product,v);
  }

  @Override public ProductResponse approve(String id) {
    ProductEntity product = findOwned(id);
    ProductProcessEntity v = latest(product);
    if (v.getStatus()!=ProductStatusEnum.PENDING_REVIEW) conflict("Only PENDING_REVIEW product can be approved");
    v.setStatus(ProductStatusEnum.APPROVED); v.setApprovedBy(AuthenticationContext.getUserId().toHexString());
    v.setApprovedAt(Instant.now()); v.setRejectionReason(null);
    saveVersion(v); syncProductSummary(product,v);
    audit.record(product,v,"APPROVE","Approved; ready for Proposition creation");
    return ProductProcessMapper.toResponse(product,v);
  }

  @Override public ProductResponse reject(String id, ProductDecisionRequest request) {
    ProductEntity product = findOwned(id);
    ProductProcessEntity v = latest(product);
    if (v.getStatus()!=ProductStatusEnum.PENDING_REVIEW) conflict("Only PENDING_REVIEW product can be rejected");
    if (request==null || request.reason()==null || request.reason().isBlank()) bad("Reject reason is required");
    v.setStatus(ProductStatusEnum.REJECTED); v.setRejectionReason(request.reason().trim());
    saveVersion(v); syncProductSummary(product,v);
    audit.record(product,v,"REJECT",v.getRejectionReason());
    return ProductProcessMapper.toResponse(product,v);
  }

  @Override public ProductResponse newVersion(String id) {
    ProductEntity product = findOwned(id);
    ProductProcessEntity source = latest(product);
    if (source.getStatus()!=ProductStatusEnum.APPROVED) conflict("Create a new version only from APPROVED version");
    product.setLatestVersion(product.getLatestVersion()+1); product.setLatestStatus(ProductStatusEnum.DRAFT);
    ProductProcessEntity next = ProductProcessEntity.builder().productId(product.getId())
      .version(product.getLatestVersion()).status(ProductStatusEnum.DRAFT)
      .name(source.getName()).description(source.getDescription()).ingredients(copy(source.getIngredients()))
      .code(source.getCode()).category(source.getCategory()).imageUrl(source.getImageUrl())
      .componentProducts(components(source.getComponentProducts()))
      .recipe(source.getRecipe()).pricing(source.getPricing()).costing(source.getCosting()).build();
    try { versionRepository.save(next); }
    catch (DuplicateKeyException ex) { conflict("A version with that number already exists"); }
    try { syncProductSummary(product,next); }
    catch (RuntimeException ex) {
      // Preserve staged process data if the database write outcome is unknown.
      if(ex instanceof ResponseStatusException response && response.getStatusCode().value()==409)
        versionRepository.deleteNewProcess(next);
      throw ex;
    }
    audit.record(product,next,"NEW_VERSION","New draft version from approved version");
    return ProductProcessMapper.toResponse(product,next);
  }

  private ProductEntity findOwned(String id) {
    ObjectId enterprise = authorization.requireEnterprise();
    if (id == null || !ObjectId.isValid(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
    return findOwned(new ObjectId(id), enterprise);
  }
  private ProductEntity findOwned(ObjectId id,ObjectId enterprise) {
    ProductEntity product = productRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found"));
    if (Boolean.TRUE.equals(product.getDeleted())) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");
    authorization.requireOwned(product.getEnterpriseId(),enterprise);
    return product;
  }
  private ProductProcessEntity latest(ProductEntity p) {
    return versionRepository.findByProductIdAndVersion(p.getId(),p.getLatestVersion())
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,"Latest product version is missing"));
  }
  private void requireAvailableCode(ObjectId enterprise, String code, ObjectId currentId) {
    productRepository.findByEnterpriseIdAndCode(enterprise,code).ifPresent(existing -> {
      if (!existing.getId().equals(currentId)) conflict("Product code already exists in this enterprise");
    });
  }
  private void requireStandalone(ProductEntity product) {
    if (product.getType() != ProductTypeEnum.STANDALONE) bad("Base product must be a STANDALONE product");
    if (product.getSourceProductId() != null || !components(latest(product).getComponentProducts()).isEmpty())
      bad("Standalone reference contains invalid dependencies");
  }
  private void requireMaster(ProductEntity product) {
    if (product.getType() != ProductTypeEnum.MASTER) bad("Adapted product must derive from a MASTER product");
    if (versionRepository.findByProductIdOrderByVersionDesc(product.getId()).stream()
        .noneMatch(process -> process.getVersion() <= product.getLatestVersion() && process.getStatus() == ProductStatusEnum.APPROVED))
      bad("Base MASTER product must have an APPROVED version");
  }
  private void validateSource(ProductEntity product) {
    if (product.getType() == ProductTypeEnum.ADAPTED) {
      if (product.getSourceProductId() == null) bad("Adapted product requires sourceProductId");
      if (product.getId().equals(product.getSourceProductId())) bad("Circular product dependency");
      ProductEntity base=findOwned(product.getSourceProductId(), product.getEnterpriseId());
      requireMaster(base);
      var path=new HashSet<ObjectId>();path.add(product.getId());
      validateDependencyGraph(base,product.getEnterpriseId(),path,new HashSet<>());
    } else if (product.getSourceProductId() != null) bad("Only ADAPTED products may reference a base product");
  }
  private void validateComponents(ProductTypeEnum type, List<ProductComponentRequest> items,
                                  ObjectId enterprise, ObjectId currentId) {
    List<ProductComponentRequest> list = items == null ? List.of() : items;
    if (type != ProductTypeEnum.MASTER) {
      if (!list.isEmpty()) bad("Only MASTER products may contain component products");
      return;
    }
    if (list.size() < 2) bad("Master product requires at least two distinct component products");
    var ids = new HashSet<ObjectId>();
    for (ProductComponentRequest item : list) {
      if (item == null || item.productId() == null || !ObjectId.isValid(item.productId())
          || item.quantity() == null || item.quantity().signum() <= 0) bad("Invalid component product or quantity");
      ObjectId id = new ObjectId(item.productId());
      if (id.equals(currentId)) bad("Circular product dependency");
      if (!ids.add(id)) bad("Duplicate component product");
      ProductEntity child = findOwned(id,enterprise);
      if (child.getType() != ProductTypeEnum.STANDALONE && child.getType() != ProductTypeEnum.ADAPTED)
        bad("Master components must be STANDALONE or ADAPTED products");
      var path=new HashSet<ObjectId>();if(currentId!=null)path.add(currentId);
      validateDependencyGraph(child,enterprise,path,new HashSet<>());
    }
  }
  /** Adapted -> Master -> components may form cycles, so validate the full dependency path. */
  private void validateDependencyGraph(ProductEntity product,ObjectId enterprise,
      java.util.Set<ObjectId> path,java.util.Set<ObjectId> visited) {
    if(path.contains(product.getId()))bad("Circular product dependency");
    if(!visited.add(product.getId()))return;
    path.add(product.getId());
    var children=components(latest(product).getComponentProducts());
    if(product.getType()==ProductTypeEnum.STANDALONE)requireStandalone(product);
    else if(product.getType()==ProductTypeEnum.ADAPTED) {
      if(product.getSourceProductId()==null||!children.isEmpty())bad("Adapted product has invalid dependencies");
      var base=findOwned(product.getSourceProductId(),enterprise);requireMaster(base);
      validateDependencyGraph(base,enterprise,path,visited);
    } else if(product.getType()==ProductTypeEnum.MASTER) {
      if(product.getSourceProductId()!=null||children.size()<2)bad("Master reference has invalid dependencies");
      var ids=new HashSet<ObjectId>();
      for(var item:children) {
        if(item==null||item.productId()==null||!ObjectId.isValid(item.productId())||item.quantity()==null||item.quantity().signum()<=0)bad("Invalid component product or quantity");
        var id=new ObjectId(item.productId());if(!ids.add(id))bad("Duplicate component product");
        var child=findOwned(id,enterprise);
        if(child.getType()!=ProductTypeEnum.STANDALONE&&child.getType()!=ProductTypeEnum.ADAPTED)bad("Master components must be STANDALONE or ADAPTED products");
        validateDependencyGraph(child,enterprise,path,visited);
      }
    } else bad("Invalid product type");
    path.remove(product.getId());
  }
  private static List<ProductComponentRequest> components(List<ProductComponentRequest> items) {
    return items == null ? List.of() : List.copyOf(items);
  }
  private static List<ProductIngredientRequest> copy(List<ProductIngredientRequest> ingredients) {
    return ingredients==null ? List.of() : List.copyOf(ingredients);
  }
  private static ProductPricingRequest fixedPricing(ProductPricingRequest pricing) {
    return pricing == null ? null : new ProductPricingRequest(pricing.sellingPrice(), pricing.costPrice(), "VND", pricing.note());
  }
  private void saveVersion(ProductProcessEntity version) {
    try { versionRepository.save(version); }
    catch (OptimisticLockingFailureException ex) { conflict("Product version conflict; reload before retrying"); }
    catch (DuplicateKeyException ex) { conflict("Product process was published concurrently; reload before retrying"); }
  }
  private void syncProductSummary(ProductEntity product,ProductProcessEntity process) {
    product.setDescription(process.getDescription());
    product.setCategory(process.getCategory()==null?List.of():List.of(process.getCategory()));
    product.setProductStatus(process.getStatus());product.setLatestStatus(process.getStatus());
    var ids=new java.util.ArrayList<>(product.getProductProcess()==null?List.of():product.getProductProcess());
    if(process.getId()!=null&&!ids.contains(process.getId()))ids.add(process.getId());
    product.setProductProcess(List.copyOf(ids));saveProduct(product);
  }
  private void saveProduct(ProductEntity product) {
    try { productRepository.save(product); }
    catch (OptimisticLockingFailureException ex) { conflict("Product modified concurrently; reload before retrying"); }
    catch (DuplicateKeyException ex) { conflict("Product code already exists in this enterprise"); }
  }
  private static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
  private static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
