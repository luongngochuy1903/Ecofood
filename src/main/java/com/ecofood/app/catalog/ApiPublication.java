package com.ecofood.app.catalog;

import java.time.Instant;
import java.util.*;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** One document per Tenant + Catalog type + API key + external version. */
@Data
@Document("api_publication")
public class ApiPublication {
  public enum CatalogType { PRODUCT, PRICE }
  public enum Status { DRAFT, VALIDATED, PUBLISHED, RETIRED }
  @Id private String id;
  private String tenantId, apiKey, name;
  private CatalogType catalogType;
  private int version, contractVersion = 1;
  private long revision;
  private Status status;
  private CatalogOrchestrator catalogOrchestrator;
  private List<CatalogDtos.Selection> selections;
  private List<Pin> pins;
  private List<String> partnerIds;
  private CatalogDtos.Policy policy;
  private String endpoint, createdBy, updatedBy, publishedBy;
  private Instant createdAt, updatedAt, validatedAt, publishedAt, retiredAt;
  private Map<String, PartnerAccess> grants = new LinkedHashMap<>();
  private List<Audit> audit = List.of();
  private long accessCount, deniedCount;
  private Instant lastAccessAt, lastDeniedAt;
  private String lastAccessPartnerId, lastDeniedCode, lastDeniedPartnerId;
  public record Pin(String propositionId, String planId, int planVersion, String productId,
      String productProcessId, int productVersion, Long processRevision, String definitionAttributeId, String publicFingerprint) {}
  public record PartnerAccess(String id, String partnerId, String credentialHash, List<String> planIds,
      Instant expiresAt, Instant issuedAt, String issuedBy, Instant revokedAt) {}
  public record Audit(String id, String actor, String tenantId, String apiKey, int version, Instant at,
      String action, String result, String detail) {}
}
