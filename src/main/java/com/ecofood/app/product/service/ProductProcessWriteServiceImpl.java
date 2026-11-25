package com.ecofood.app.product.service;

import com.ecofood.app.product.entity.ProductProcessEntity;
import com.ecofood.core.dto.ProductFamilySaveRequest;
import com.ecofood.core.dto.ProductProcessResponse;
import com.ecofood.core.enums.ProductStatusEnum;
import com.ecofood.core.service.ProductProcessWriteService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Backs CoreProductProcessClient — always version 1, keyed by a caller-
 * supplied family id (a Proposition's Plan, today) rather than a real
 * ProductEntity. Concurrency is the caller's own responsibility (e.g. a
 * Proposition's own optimistic-locked revision already gates this call);
 * re-saving the same family id updates its existing process in place,
 * preserving whatever review status it already carried.
 */
@Service
@RequiredArgsConstructor
public class ProductProcessWriteServiceImpl implements ProductProcessWriteService {
  private final ProductProcessStore store;
  private final ProductProcessMapper mapper;

  @Override
  public ProductProcessResponse save(String familyId, ProductFamilySaveRequest r) {
    if (familyId == null || !ObjectId.isValid(familyId)) bad("Invalid family id");
    if (r == null || r.name() == null || r.name().isBlank()) bad("Name is required");
    if (r.imageUrl() != null && !r.imageUrl().isBlank()) {
      try {
        var uri = java.net.URI.create(r.imageUrl());
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null) bad("Image must be an HTTP(S) URL");
      } catch (IllegalArgumentException ex) {
        bad("Image must be an HTTP(S) URL");
      }
    }
    ObjectId id = new ObjectId(familyId);
    var existing = store.findByProductIdAndVersion(id, 1);
    var builder = ProductProcessEntity.builder()
        .productId(id)
        .version(1)
        .name(r.name().trim())
        .description(r.description())
        .category(r.category() == null ? null : r.category().trim())
        .imageUrl(r.imageUrl())
        .ingredients(r.ingredients() == null ? List.of() : List.copyOf(r.ingredients()))
        .recipe(r.recipe())
        .costing(r.costing())
        .pricing(r.pricing());
    ProductProcessEntity p;
    if (existing.isPresent()) {
      var old = existing.get();
      p = builder.status(old.getStatus()).rejectionReason(old.getRejectionReason())
          .approvedBy(old.getApprovedBy()).approvedAt(old.getApprovedAt()).build();
      p.setId(old.getId());
      p.setRevision(old.getRevision());
      p.setCreatedAt(old.getCreatedAt());
      p.setCreatedBy(old.getCreatedBy());
    } else {
      p = builder.status(ProductStatusEnum.DRAFT).build();
    }
    return mapper.process(store.save(p));
  }

  @Override
  public ProductProcessResponse get(String familyId) {
    if (familyId == null || !ObjectId.isValid(familyId)) bad("Invalid family id");
    var p = store.findByProductIdAndVersion(new ObjectId(familyId), 1)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan content not found"));
    return mapper.process(p);
  }

  private static void bad(String message) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
