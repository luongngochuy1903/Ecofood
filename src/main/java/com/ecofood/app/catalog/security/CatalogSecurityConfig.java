package com.ecofood.app.catalog.security;

import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
public class CatalogSecurityConfig {
  @Bean @Order(1)
  SecurityFilterChain externalCatalogChain(HttpSecurity http,CatalogExternalAccess access,@org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource") CorsConfigurationSource corsConfigurationSource) throws Exception {
    return http.securityMatcher("/api/external/v1/catalog/**").csrf(AbstractHttpConfigurer::disable)
        .cors(cors->cors.configurationSource(corsConfigurationSource)).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a->a.anyRequest().hasAuthority("CATALOG_READ"))
        .exceptionHandling(e->e.authenticationEntryPoint((request,response,error)->CatalogExternalFilter.error(response,401,"External Catalog authentication required"))
            .accessDeniedHandler((request,response,error)->CatalogExternalFilter.error(response,403,"External Catalog access denied")))
        .addFilterBefore(new CatalogExternalFilter(access),UsernamePasswordAuthenticationFilter.class).build();
  }
}
