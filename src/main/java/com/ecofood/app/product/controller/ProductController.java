package com.ecofood.app.product.controller;

import com.ecofood.app.config.Audited;
import com.ecofood.core.enums.AuditAction;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import com.ecofood.core.service.ProductQueryService;
import com.ecofood.core.service.ProductService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/products")
public class ProductController {
  private final ProductService productService;
  private final ProductQueryService productQueryService;
  @GetMapping
  public Page<ProductResponse> list(@RequestParam(required=false) String search,
      @RequestParam(required=false) ProductTypeEnum type,
      @RequestParam(required=false) ProductStatusEnum status,Pageable pageable) {
    return productQueryService.list(search,type,status,pageable);
  }
  @GetMapping("/{id}") public ProductResponse detail(@PathVariable String id) {
    return productQueryService.getById(id);
  }
  @Audited(AuditAction.CREATE)
  @PostMapping public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(request));
  }
  @Audited(AuditAction.UPDATE)
  @PutMapping("/{id}") public ProductResponse update(@PathVariable String id,
      @Valid @RequestBody ProductUpdateRequest request) {
    return productService.update(id,request);
  }
  @Audited(AuditAction.UPDATE)
  @PostMapping("/{id}/submit") public ProductResponse submit(@PathVariable String id) {
    return productService.submit(id);
  }
  @Audited(AuditAction.UPDATE)
  @PostMapping("/{id}/approve") public ProductResponse approve(@PathVariable String id) {
    return productService.approve(id);
  }
  @Audited(AuditAction.UPDATE)
  @PostMapping("/{id}/reject") public ProductResponse reject(@PathVariable String id,
      @Valid @RequestBody ProductDecisionRequest request) {
    return productService.reject(id,request);
  }
  @GetMapping("/{id}/versions") public List<ProductVersionResponse> versions(@PathVariable String id) {
    return productQueryService.versions(id);
  }
  @GetMapping("/{id}/processes") public List<ProductProcessResponse> processes(@PathVariable String id) {
    return productQueryService.processes(id);
  }
  @GetMapping("/{id}/processes/{version}") public ProductResponse process(@PathVariable String id,@PathVariable int version) {
    return productQueryService.getVersion(id,version);
  }
  @Audited(AuditAction.CREATE)
  @PostMapping("/{id}/versions") public ResponseEntity<ProductResponse> newVersion(@PathVariable String id) {
    return ResponseEntity.status(HttpStatus.CREATED).body(productService.newVersion(id));
  }
}
