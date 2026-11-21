package com.ecofood.app.catalog;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

/** Constructed only inside the external SecurityFilterChain; never registered as a global servlet filter. */
@RequiredArgsConstructor
public class CatalogExternalFilter extends OncePerRequestFilter {
  private final CatalogExternalAccess access;
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
    try {
      boolean loopback=List.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())
          && List.of("localhost","127.0.0.1","::1","[::1]").contains(request.getServerName().toLowerCase(java.util.Locale.ROOT));
      if(!request.isSecure() && !loopback) { error(response,403,"HTTPS required for external credentials"); return; }
      CatalogDtos.ExternalPrincipal principal;
      try { principal=access.authenticate(request.getHeader("Authorization")); }
      catch(ResponseStatusException invalid) { error(response,invalid.getStatusCode().value(),invalid.getReason()); return; }
      SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of(new SimpleGrantedAuthority("CATALOG_READ"))));
      response.setHeader("Cache-Control","no-store"); chain.doFilter(request,response);
    } finally { SecurityContextHolder.clearContext(); }
  }
  static void error(HttpServletResponse response,int status,String message) throws IOException {
    response.setStatus(status); response.setHeader("Cache-Control","no-store"); response.setContentType("application/json;charset=UTF-8");
    // Messages are fixed server-controlled codes, not request data.
    response.getWriter().write(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("message",message==null?"Catalog authentication failed":message)));
  }
}
