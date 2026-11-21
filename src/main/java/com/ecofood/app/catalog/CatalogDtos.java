package com.ecofood.app.catalog;

import com.ecofood.core.dto.MarketConfigurationResponse;
import java.time.Instant;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class CatalogDtos {
  private CatalogDtos() {}
  public record Selection(@NotBlank String propositionId, @NotBlank String planId) {}
  public record Policy(@NotNull Instant startsAt, @NotNull Instant expiresAt, @Min(1) @Max(100) int maxPageSize) {}
  public record Draft(@NotBlank String apiKey, @NotBlank @Size(max=150) String name, @NotNull ApiPublication.CatalogType catalogType,
      @Min(1) @Max(10000) int version, @NotEmpty @Size(max=100) List<@Valid Selection> selections,
      @NotEmpty @Size(max=50) List<String> partnerIds, @NotNull @Valid Policy policy,
      List<CatalogOrchestrator.CustomField> customField, Long revision) {}
  public record Revision(@Min(1) long revision) {}
  public record Grant(@Min(1) long revision, @NotBlank String partnerId, List<String> planIds, @NotNull Instant expiresAt) {}
  public record GrantView(String id, String partnerId, List<String> planIds, Instant expiresAt, Instant issuedAt, String issuedBy, Instant revokedAt, boolean effective) {}
  public record View(String id, String tenantId, String apiKey, String name, ApiPublication.CatalogType catalogType,
      int version, int contractVersion, long revision, ApiPublication.Status status, CatalogOrchestrator catalogOrchestrator,
      List<Selection> selections, List<ApiPublication.Pin> pins, List<String> partnerIds, Policy policy, String endpoint,
      Instant createdAt, Instant updatedAt, Instant validatedAt, Instant publishedAt, Instant retiredAt,
      List<GrantView> grants, List<ApiPublication.Audit> audit, long accessCount, Instant lastAccessAt, String lastAccessPartnerId,
      long deniedCount, Instant lastDeniedAt, String lastDeniedCode, String lastDeniedPartnerId) {}
  public record Credential(String publicationId, String partnerId, String credential, Instant expiresAt, String endpoint) {
    @Override public String toString() { return "CatalogCredential[REDACTED]"; }
  }
  public record Page<T>(List<T> content, int page, int size, boolean hasNext) {}
  public record Capabilities(String tenantId, boolean read, boolean manage, boolean publish, boolean grant) {}
  public record PartnerOption(String id, String name) {}
  public record PartnerScope(String partnerId, String partnerName, List<Item> entries) {}
  public record PlanOption(String propositionId, String propositionName, String planId, String planName, int planVersion,
      String productId, int productVersion, String currency, List<String> eligiblePartnerIds) {}
  public record Options(List<ApiPublication.CatalogType> catalogTypes, List<String> unsupportedCatalogs, List<PartnerOption> partners) {}
  /** Explicit external contract. No recipe, ingredients, Costing, profit share or internal entity serialization. */
  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  public record Item(String propositionId, String planId, int planVersion, String productId, int productVersion,
      String code, String name, String description, String category, String imageUrl, MarketConfigurationResponse price) {}
  public record Catalog(String apiKey, int version, int contractVersion, ApiPublication.CatalogType catalogType,
      List<Item> content, int page, int size, boolean hasNext) {}
  public record ExternalPrincipal(String publicationId, String partnerId, String grantId) {}
  public record Session(String publicationId, String partnerId, String apiKey, int version, Instant expiresAt) {}
}
