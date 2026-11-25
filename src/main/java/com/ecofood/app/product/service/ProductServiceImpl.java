package com.ecofood.app.product.service;
import com.ecofood.app.product.entity.*;
import com.ecofood.app.product.repository.*;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ProductService;
import com.ecofood.core.service.PropositionService;
import com.ecofood.core.service.SequenceGeneratorService;
import com.ecofood.core.util.FormatCode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
  private final ProductVersionReviewService reviews;
  private final PropositionService propositions;
  private final SequenceGeneratorService sequenceGeneratorService;
  private final ProductProcessMapper productProcessMapper;

  @Override public ProductResponse create(ProductCreateRequest request) {
    ObjectId enterprise = authorization.requireEnterprise();
    if (request == null || request.type() == null) bad("Product type is required");
    validation.validateDraft(request.name(),request.ingredients(),request.recipe(),request.pricing());
    // Server-generated, same pattern as TenantEntity's onboarding code — never
    // user-provided (see ProductCreateRequest javadoc).
    long sequence = sequenceGeneratorService.generateSequence(ProductEntity.SEQUENCE_NAME);
    String code = FormatCode.byPaddingZeros(sequence, 5, "PR");
    validation.validateDefinition(code,request.category(),request.imageUrl());
    requireAvailableCode(enterprise, code, null);
    ProductEntity product = ProductEntity.builder().enterpriseId(enterprise).type(request.type())
      .code(code).latestVersion(1).latestStatus(ProductStatusEnum.DRAFT).build();
    // Assign the ObjectId up-front: version and parent share a stable id before either save.
    product.setId(new ObjectId());
    ProductProcessEntity version = ProductProcessEntity.builder().productId(product.getId()).version(1)
      .status(ProductStatusEnum.DRAFT).name(request.name().trim()).description(request.description())
      .code(code).category(request.category().trim()).imageUrl(request.imageUrl())
      .ingredients(copy(request.ingredients())).recipe(request.recipe()).pricing(fixedPricing(request.pricing())).costing(request.costing()).build();
    version.setId(new ObjectId());
    product.setDescription(version.getDescription());product.setCategory(List.of(version.getCategory()));
    product.setProductStatus(version.getStatus());product.setProductProcess(List.of(version.getId()));
    saveProduct(product);
    try { versionRepository.save(version); }
    catch (RuntimeException ex) { productRepository.deleteById(product.getId()); throw ex; }
    audit.record(product,version,"CREATE","Draft created");
    return productProcessMapper.toResponse(product,version);
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
    if (product.getCode() == null) { product.setCode(code); saveProduct(product); }
    v.setCode(code); v.setCategory(request.category().trim()); v.setImageUrl(request.imageUrl());
    v.setName(request.name().trim()); v.setDescription(request.description());
    v.setIngredients(copy(request.ingredients())); v.setRecipe(request.recipe()); v.setPricing(fixedPricing(request.pricing()));v.setCosting(request.costing());
    if (v.getStatus()==ProductStatusEnum.REJECTED) {
      v.setStatus(ProductStatusEnum.DRAFT); v.setRejectionReason(null);
    }
    saveVersion(v);
    syncProductSummary(product,v);
    audit.record(product,v,"UPDATE","Draft updated");
    return productProcessMapper.toResponse(product,v);
  }

  @Override public ProductResponse submit(String id) {
    ProductEntity product = findOwned(id);
    ProductProcessEntity v = latest(product);
    if (v.getStatus()!=ProductStatusEnum.DRAFT) conflict("Only DRAFT product can be submitted");
    validation.validateSubmit(v);
    requireAvailableCode(product.getEnterpriseId(), v.getCode(), product.getId());
    v.setStatus(ProductStatusEnum.PENDING_REVIEW);
    saveVersion(v); syncProductSummary(product,v);
    audit.record(product,v,"SUBMIT","Submitted for review");
    return productProcessMapper.toResponse(product,v);
  }

  @Override public ProductResponse approve(String id, int versionNo, ProductApproveRequest request) {
    ProductEntity product = findOwned(id);
    ProductProcessEntity v = version(product,versionNo);
    if (v.getStatus()!=ProductStatusEnum.PENDING_REVIEW) conflict("Only PENDING_REVIEW product can be approved");
    v.setStatus(ProductStatusEnum.APPROVED); v.setApprovedBy(AuthenticationContext.getUserId().toHexString());
    v.setApprovedAt(Instant.now()); v.setRejectionReason(null);
    saveVersion(v);
    // Only resync the product-level summary fields (description/category/
    // productStatus/latestStatus) when the version just approved IS the
    // current latest — approving an OLDER version must never make the
    // product's own summary fields regress to that older version's content.
    if (v.getVersion()==product.getLatestVersion()) syncProductSummary(product,v);
    demoteAnyOtherApprovedVersion(product,v);
    // Plans the caller chose in the pending-plan-approvals popup (bound to
    // THIS version, still Pending Review) become Approved alongside it.
    var approvePlanIds = request == null ? List.<String>of() : request.approvePlanIds();
    propositions.approvePlans(product.getIdAsString(), versionNo, approvePlanIds);
    audit.record(product,v,"APPROVE","Approved; ready for Proposition creation");
    return productProcessMapper.toResponse(product,v);
  }

  @Override public List<PendingPlanApprovalResponse> pendingPlanApprovals(String id, int versionNo) {
    ProductEntity product = findOwned(id);
    return propositions.pendingPlanApprovals(product.getIdAsString(), versionNo);
  }

  @Override public ProductResponse reject(String id, int versionNo, ProductDecisionRequest request) {
    ProductEntity product = findOwned(id);
    ProductProcessEntity v = version(product,versionNo);
    if (v.getStatus()!=ProductStatusEnum.PENDING_REVIEW) conflict("Only PENDING_REVIEW product can be rejected");
    if (request==null || request.reason()==null || request.reason().isBlank()) bad("Reject reason is required");
    v.setStatus(ProductStatusEnum.REJECTED); v.setRejectionReason(request.reason().trim());
    saveVersion(v);
    if (v.getVersion()==product.getLatestVersion()) syncProductSummary(product,v);
    audit.record(product,v,"REJECT",v.getRejectionReason());
    return productProcessMapper.toResponse(product,v);
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
    // The source APPROVED version is left exactly as it was — creating a new
    // draft version must never downgrade it to PENDING_REVIEW. (Approving a
    // DIFFERENT, older version still demotes whichever version currently
    // holds APPROVED — see demoteAnyOtherApprovedVersion — since only one
    // version may be APPROVED at a time; that's a separate invariant from
    // this one.)
    return productProcessMapper.toResponse(product,next);
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
  private ProductProcessEntity version(ProductEntity p,int versionNo) {
    return versionRepository.findByProductIdAndVersion(p.getId(),versionNo)
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Product version not found"));
  }
  /**
   * Re-approving an older version (see newVersion's own symmetric cascade)
   * must demote whichever OTHER version currently holds APPROVED — only one
   * version may be APPROVED at a time. Reuses the exact same queued,
   * retry-safe cascade ProductVersionReviewService already provides for
   * newVersion(), rather than reimplementing it. Any Plan — across every
   * Proposition sourced from this Product — bound to that now-demoted
   * version and currently Approved is taken back to Pending Review too.
   */
  private void demoteAnyOtherApprovedVersion(ProductEntity product, ProductProcessEntity justApproved) {
    versionRepository.findByProductIdOrderByVersionDesc(product.getId()).stream()
      .filter(p -> p.getVersion()!=justApproved.getVersion() && p.getStatus()==ProductStatusEnum.APPROVED)
      .findFirst()
      .ifPresent(previouslyApproved -> {
        var pendingReviews = new java.util.ArrayList<>(product.getPendingVersionReviews() == null ? List.<Integer>of() : product.getPendingVersionReviews());
        if (!pendingReviews.contains(previouslyApproved.getVersion())) pendingReviews.add(previouslyApproved.getVersion());
        product.setPendingVersionReviews(List.copyOf(pendingReviews));
        saveProduct(product);
        reviews.apply(product, previouslyApproved.getVersion());
        propositions.demoteApprovedPlans(product.getIdAsString(), previouslyApproved.getVersion());
      });
  }
  private void requireAvailableCode(ObjectId enterprise, String code, ObjectId currentId) {
    productRepository.findByEnterpriseIdAndCode(enterprise,code).ifPresent(existing -> {
      if (!existing.getId().equals(currentId)) conflict("Product code already exists in this enterprise");
    });
  }
  private static List<ProductIngredientRequest> copy(List<ProductIngredientRequest> ingredients) {
    return ingredients==null ? List.of() : List.copyOf(ingredients);
  }
  private static ProductPricingRequest fixedPricing(ProductPricingRequest pricing) {
    return pricing == null ? null : new ProductPricingRequest(pricing.sellingPrice(), pricing.costPrice(), ProductCurrency.normalize(pricing.currency()), pricing.note());
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
