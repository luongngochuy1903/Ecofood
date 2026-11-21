package com.ecofood.app.orchestrator.service;

import com.ecofood.app.config.*;
import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
import com.ecofood.app.orchestrator.repository.PlanSaleOrchestratorRepository;
import com.ecofood.app.orchestrator.security.PlanSaleAccess;
import com.ecofood.app.orchestrator.validation.SaleSchemaValidator;
import com.ecofood.core.dto.*;
import com.ecofood.core.dto.PreparedSaleTransaction;
import com.ecofood.core.dto.SaleOrchestratorConfig;
import com.ecofood.core.dto.SubmitTransactionRequest;
import com.ecofood.core.service.*;
import com.ecofood.core.service.SaleOrchestratorConfigProvider;
import com.ecofood.core.service.SaleOrchestratorRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validator;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** UC-014 prepares data; the existing/future UC-015 remains the only Invoice writer. */
@Service
@RequiredArgsConstructor
public class PersistentPlanSaleRuntime
    implements SaleOrchestratorConfigProvider, SaleOrchestratorRuntime {
  private final PlanSaleOrchestratorRepository repository;
  private final PlanSaleAccess access;
  private final SaleSchemaValidator schemas;
  private final Validator validator;
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Override
  public Optional<SaleOrchestratorConfig> findForPos(String tenantId, String subject) {
    if (tenantId == null || tenantId.isBlank() || subject == null || subject.isBlank())
      return Optional.empty();
    var rows = repository.findByTenantIdAndActiveVersionNotNull(tenantId);
    if (rows.size() > 1)
      throw new IllegalStateException(
          "Tenant has multiple Plan configurations; resolve by Orchestrator ID");
    return rows.stream().findFirst().map(row -> binding(row, subject));
  }

  @Override
  public PreparedSaleTransaction prepare(
      String tenantId, String subject, String id, Map<String, Object> payload) {
    if (tenantId == null || tenantId.isBlank() || subject == null || subject.isBlank())
      throw new ForbiddenException("Verified caller Tenant and subject required");
    var row =
        repository
            .findByIdAndTenantId(id, tenantId)
            .orElseThrow(
                () -> new ForbiddenException("Orchestrator is unavailable for this Tenant"));
    var version = PlanSaleOrchestratorServiceImpl.active(row);
    var c = version.configuration();
    var binding = binding(row, subject);
    var normalized = schemas.apply(c.payloadSchema(), payload);
    var fields = new LinkedHashMap<String, Object>();
    for (String key :
        List.of("invoiceNumber", "transactionTime", "currency", "totalAmount", "items"))
      fields.put(key, normalized.get(key));
    // Custom item fields stay in the snapshot, while the original UC-015 DTO keeps its five fields.
    if (normalized.get("items") instanceof List<?> items)
      fields.put(
          "items",
          items.stream()
              .map(
                  raw -> {
                    var item = (Map<?, ?>) raw;
                    var dto = new LinkedHashMap<String, Object>();
                    for (String key :
                        List.of("productCode", "productName", "quantity", "unitPrice", "amount"))
                      dto.put(key, item.get(key));
                    return dto;
                  })
              .toList());
    SubmitTransactionRequest transaction;
    try {
      transaction = mapper.convertValue(fields, SubmitTransactionRequest.class);
    } catch (IllegalArgumentException ex) {
      throw new BadRequestException("Payload cannot map to UC-015 transaction DTO");
    }
    if (!validator.validate(transaction).isEmpty())
      throw new BadRequestException("Payload violates required UC-015 transaction constraints");
    if (!binding.currencies().contains(transaction.currency())
        || transaction.items().size() > binding.maxItems())
      throw new BadRequestException("Payload violates Plan currency or item limit");
    return new PreparedSaleTransaction(
        row.getId(),
        version.version(),
        binding,
        transaction,
        normalized,
        c.postWorkflows().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()));
  }

  private SaleOrchestratorConfig binding(PlanSaleOrchestrator row, String subject) {
    var c = PlanSaleOrchestratorServiceImpl.active(row).configuration();
    var scope = access.require(row.getTenantId(), row.getPropositionId(), row.getPlanId());
    access.active(scope);
    return new SaleOrchestratorConfig(
        row.getTenantId(),
        subject, // Preserve the UC-015 caller identifier without a separately configured POS
        // registry.
        row.getTenantId(),
        row.getPlanId(),
        row.getPropositionId(),
        true,
        true,
        true,
        Set.of(access.currency(scope)),
        c.maxItems());
  }
}
