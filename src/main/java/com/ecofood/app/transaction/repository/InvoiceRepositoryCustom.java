package com.ecofood.app.transaction.repository;

import com.ecofood.app.transaction.dto.TransactionFilter;
import com.ecofood.app.transaction.dto.TransactionSummaryResponse.CurrencyTotal;
import com.ecofood.app.transaction.entity.Invoice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InvoiceRepositoryCustom {
  Page<Invoice> findTransactions(List<String> tenantIds, boolean unrestricted,
      TransactionFilter filter, Pageable pageable);
  Optional<Invoice> findScopedInvoice(String id, List<String> tenantIds, boolean unrestricted);
  List<CurrencyTotal> summarizeTransactions(List<String> tenantIds, boolean unrestricted,
      TransactionFilter filter);
}
