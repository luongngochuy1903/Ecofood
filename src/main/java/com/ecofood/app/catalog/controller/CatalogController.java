package com.ecofood.app.catalog.controller;

import static com.ecofood.core.dto.CatalogDtos.*;

import com.ecofood.core.dto.CatalogDtos;
import com.ecofood.core.enums.CatalogTypeEnum;
import com.ecofood.core.service.CatalogPublicationService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/catalog-publications")
public class CatalogController {
  private final CatalogPublicationService service;
  @GetMapping("/capabilities") public Capabilities capabilities() { return service.capabilities(); }
  @GetMapping("/options") public Options options(@RequestParam(defaultValue="PRODUCT") CatalogTypeEnum type) { return service.options(type); }
  @GetMapping("/plans") public Page<PlanOption> plans(@RequestParam(defaultValue="PRODUCT") CatalogTypeEnum type,@RequestParam(required=false) String search,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.plans(type,search,page,size); }
  @GetMapping public Page<View> list(@RequestParam(required=false) CatalogTypeEnum type,@RequestParam(required=false) String apiKey,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.list(type,apiKey,page,size); }
  @PostMapping @ResponseStatus(HttpStatus.CREATED) public View create(@Valid @RequestBody Draft body) { return service.save(null,body); }
  @GetMapping("/{id}") public View detail(@PathVariable String id) { return service.detail(id); }
  @PutMapping("/{id}") public View update(@PathVariable String id,@Valid @RequestBody Draft body) { return service.save(id,body); }
  @GetMapping("/{id}/versions") public Page<View> versions(@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { var p=service.detail(id); return service.list(p.catalogType(),p.apiKey(),page,size); }
  @GetMapping("/{id}/audit") public List<CatalogDtos.Audit> audit(@PathVariable String id) { return service.audit(id); }
  @PostMapping("/{id}/validate") public View validate(@PathVariable String id,@Valid @RequestBody Revision body) { return service.validate(id,body); }
  @PostMapping("/{id}/publish") public View publish(@PathVariable String id,@Valid @RequestBody Revision body) { return service.publish(id,body); }
  @PostMapping("/{id}/retire") public View retire(@PathVariable String id,@Valid @RequestBody Revision body) { return service.retire(id,body); }
  @PostMapping("/{id}/partners") public Credential grant(@PathVariable String id,@Valid @RequestBody Grant body) { return service.grant(id,body); }
  @GetMapping("/{id}/partner-options") public List<PartnerScope> partnerOptions(@PathVariable String id) { return service.partnerOptions(id); }
  @PostMapping("/{id}/partners/{partnerId}/revoke") public View revoke(@PathVariable String id,@PathVariable String partnerId,@Valid @RequestBody Revision body) { return service.revoke(id,partnerId,body); }
}
