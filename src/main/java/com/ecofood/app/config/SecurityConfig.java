package com.ecofood.app.config;

import com.ecofood.app.auth.security.InternalJwtAuthenticationEntryPoint;
import com.ecofood.app.auth.security.InternalJwtRequestFilter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Onboarding/auth-bootstrap endpoints stay open; everything else requires an internal access token. */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final InternalJwtRequestFilter internalJwtRequestFilter;
  private final InternalJwtAuthenticationEntryPoint internalJwtAuthenticationEntryPoint;

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/v1/users/directory", "/api/v1/users/{userId}")
                    .authenticated()
                    .requestMatchers(
                        // Only the exact onboarding/pre-login lookups tenants
                        // needs public — everything else under
                        // /api/v1/tenants/** (directory, /{id}, ...) falls
                        // through to the final anyRequest().authenticated()
                        // below, same as any other tenant-module endpoint.
                        // /api/v1/users/** stays fully permitAll otherwise
                        // (unlike tenants, not yet cleaned up to an exact
                        // public list) except for these two carve-outs.
                        "/api/v1/tenants",
                        "/api/v1/tenants/login-info",
                        "/api/v1/tenants/domain-availability",
                        "/api/v1/tenants/root",
                        "/api/v1/enterprises/**",
                        "/api/v1/users/**",
                        "/api/v1/auth/**",
                        "/api/v1/permissions/**",
                        "/api/v1/groups/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        // needed so the /error forward isn't masked by a 401
                        "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(a -> a.authenticationEntryPoint(internalJwtAuthenticationEntryPoint))
        .addFilterBefore(internalJwtRequestFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    // local dev origins + per-tenant *.localhost subdomains; prod needs the real domain (see README.md)
    configuration.setAllowedOriginPatterns(
        List.of("http://localhost:3000", "http://localhost:5173", "http://*.localhost:5173"));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Content-Type", "Authorization"));

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
