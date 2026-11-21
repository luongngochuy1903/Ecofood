package com.ecofood.app.catalog.entity;

import com.ecofood.core.dto.CatalogDtos;
import com.ecofood.core.dto.CatalogOrchestrator;
import com.ecofood.core.enums.CatalogPublicationStatusEnum;
import com.ecofood.core.enums.CatalogTypeEnum;
import java.time.Instant;
import java.util.*;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** One document per Tenant + Catalog type + API key + external version. */
@Data
@org.springframework.data.annotation.TypeAlias("com.ecofood.app.catalog.ApiPublication")
@Document("api_publication")
public class ApiPublication {
  @Id private String id;
  private String tenantId;
  private String apiKey;
  private String name;
  private CatalogTypeEnum catalogType;
  private int version;
  private int contractVersion = 1;
  private long revision;
  private CatalogPublicationStatusEnum status;
  private CatalogOrchestrator catalogOrchestrator;
  private List<CatalogDtos.Selection> selections;
  private List<CatalogDtos.Pin> pins;
  private List<String> partnerIds;
  private CatalogDtos.Policy policy;
  private String endpoint;
  private String createdBy;
  private String updatedBy;
  private String publishedBy;
  private Instant createdAt;
  private Instant updatedAt;
  private Instant validatedAt;
  private Instant publishedAt;
  private Instant retiredAt;
  private Map<String, PartnerAccess> grants = new LinkedHashMap<>();
  private List<CatalogDtos.Audit> audit = List.of();
  private long accessCount;
  private long deniedCount;
  private Instant lastAccessAt;
  private Instant lastDeniedAt;
  private String lastAccessPartnerId;
  private String lastDeniedCode;
  private String lastDeniedPartnerId;
  @org.springframework.data.annotation.TypeAlias("com.ecofood.app.catalog.ApiPublication$PartnerAccess")
  public record PartnerAccess(String id, String partnerId, String credentialHash, List<String> planIds,
      Instant expiresAt, Instant issuedAt, String issuedBy, Instant revokedAt) {}
}
