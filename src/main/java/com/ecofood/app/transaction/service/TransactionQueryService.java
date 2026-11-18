package com.ecofood.app.transaction.service;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.app.transaction.dto.*;
import com.ecofood.app.transaction.repository.InvoiceRepository;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.entity.RoleLevel;
import com.ecofood.core.security.AuthenticationContext;
import com.ecofood.core.service.ACLService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransactionQueryService {
  private final InvoiceRepository invoices;
  private final ACLService aclService;
  private final CoreTenantQueryClient tenantQueryClient;

  public PageResponse<TransactionListItem> list(TransactionFilter filter, int page, int size) {
    Scope scope = currentScope();
    if (page < 0 || size < 1 || size > 100)
      throw new BadRequestException("page must be >= 0 and size must be between 1 and 100");
    var pageable = PageRequest.of(page, size,
        Sort.by(Sort.Direction.DESC, "transactionTime").and(Sort.by(Sort.Direction.DESC, "id")));
    var result = invoices.findTransactions(scope.tenantIds(), scope.unrestricted(), filter, pageable);
    return PageResponse.<TransactionListItem>builder()
        .content(result.getContent().stream().map(TransactionListItem::from).toList())
        .page(result.getNumber()).size(result.getSize())
        .totalElements(result.getTotalElements()).totalPages(result.getTotalPages()).build();
  }

  public TransactionDetailResponse detail(String id) {
    Scope scope = currentScope();
    if (!ObjectId.isValid(id)) throw new BadRequestException("Invalid invoice identifier");
    return invoices.findScopedInvoice(id, scope.tenantIds(), scope.unrestricted())
        .map(TransactionDetailResponse::from)
        .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
  }

  public TransactionSummaryResponse summary(TransactionFilter filter) {
    Scope scope = currentScope();
    var totals = invoices.summarizeTransactions(scope.tenantIds(), scope.unrestricted(), filter);
    return new TransactionSummaryResponse(totals.stream().mapToLong(
        TransactionSummaryResponse.CurrencyTotal::totalTransactions).sum(), totals);
  }

  private Scope currentScope() {
    var permissions = AuthenticationContext.getPermissions();
    if (permissions == null || !permissions.contains("TRANSACTION_READ"))
      throw new ForbiddenException("Missing TRANSACTION_READ permission");
    RoleLevel level = aclService.getMaxLevel();
    if (level == RoleLevel.SYSTEM) return new Scope(List.of(), true);
    if (level != RoleLevel.TENANT && level != RoleLevel.MASTER_TENANT && level != RoleLevel.ENTERPRISE)
      throw new ForbiddenException("Caller's role level does not grant access to transactions");
    String tenantId = AuthenticationContext.getClaims().get("tenantId", String.class);
    if (tenantId == null || !ObjectId.isValid(tenantId))
      throw new ForbiddenException("Token must identify a valid tenant");
    tenantId = new ObjectId(tenantId).toHexString();
    return new Scope(level == RoleLevel.TENANT ? List.of(tenantId)
        : List.copyOf(tenantQueryClient.getScopedTenantIds(tenantId)), false);
  }

  private record Scope(List<String> tenantIds, boolean unrestricted) {}
}
