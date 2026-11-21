package com.ecofood.app.catalog.controller;

import static com.ecofood.core.dto.CatalogDtos.*;

import com.ecofood.app.catalog.security.CatalogExternalAccess;
import com.ecofood.core.service.CatalogExternalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/external/v1/catalog")
public class CatalogExternalController {
  private final CatalogExternalService service;
  private ExternalPrincipal principal(Authentication auth) {
    if(auth==null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof ExternalPrincipal p)
        || auth.getAuthorities().stream().noneMatch(a->"CATALOG_READ".equals(a.getAuthority()))) throw CatalogExternalAccess.unauthorized();
    return p;
  }
  @GetMapping("/session") public Session session(Authentication auth) { return service.session(principal(auth)); }
  @GetMapping("/{id}/versions/{version}") public Catalog read(Authentication auth,@PathVariable String id,@PathVariable int version,
      @RequestParam(required=false) String planId,@RequestParam(required=false) String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
    return service.read(principal(auth),id,version,planId,search,page,size);
  }
}
