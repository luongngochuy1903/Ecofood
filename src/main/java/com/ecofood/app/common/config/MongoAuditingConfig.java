package com.ecofood.app.common.config;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Supplies the "current user" for @CreatedBy/@LastModifiedBy. DCMP's real
 * AuditAwareImpl reads this from a custom ThreadLocal-backed
 * AuthenticationContext populated by manually parsing the JWT; this project
 * already stores the same identity (the internal token's subject) on
 * SecurityContextHolder via InternalJwtRequestFilter, so reading it from
 * there is the idiomatic-for-this-codebase equivalent — no second
 * JWT-parsing mechanism needed.
 *
 * Returns empty (leaving createdBy/updatedBy null) for both a genuinely
 * missing authentication AND Spring Security's default
 * AnonymousAuthenticationToken — otherwise every record created by the
 * public, unauthenticated onboarding endpoints (tenant/user creation) would
 * incorrectly get stamped with the literal string "anonymousUser".
 */
@Configuration
public class MongoAuditingConfig {

  @Bean
  public AuditorAware<String> auditorAware() {
    return () -> {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null
          || !authentication.isAuthenticated()
          || authentication instanceof AnonymousAuthenticationToken) {
        return Optional.empty();
      }
      return Optional.ofNullable(authentication.getName());
    };
  }
}
