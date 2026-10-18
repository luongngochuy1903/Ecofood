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

/**
 * Tenant onboarding, user creation, and the auth exchange/refresh/logout
 * endpoints are all either onboarding-time or auth-bootstrapping operations
 * with no internal token to present yet, so they stay open. Everything else
 * requires a valid internal access token, checked by InternalJwtRequestFilter
 * — this is what makes the auth module's token actually mean something for
 * any future protected resource. OpenAPI's own routes are left open too so
 * the API docs are reachable without auth during local development.
 */
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
                auth.requestMatchers(
                        "/api/v1/tenants/**",
                        "/api/v1/users/**",
                        "/api/v1/auth/**",
                        "/api/v1/permissions/**",
                        "/api/v1/groups/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        // Spring Security also guards the internal forward Spring
                        // Boot makes to /error after an unhandled exception —
                        // without this, a genuine 500 from a permitAll endpoint
                        // above gets masked by this filter chain's own 401 on
                        // that forwarded request instead of the real status.
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
    configuration.setAllowedOrigins(List.of("http://localhost:3000"));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Content-Type", "Authorization"));

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }
}
