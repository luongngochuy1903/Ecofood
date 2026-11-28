package com.ecofood.app.metrics;

import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.entity.InvoiceSnapshot;
import com.ecofood.core.dto.InvoiceItemDto;
import com.ecofood.core.enums.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

final class MetricsFixtures {
  static BigDecimal bd(String value) { return value == null ? null : new BigDecimal(value); }

  static Invoice invoice(String id, String tenant, String proposition, String currency, String net) {
    var invoice = new Invoice();
    invoice.setId(id);
    invoice.setTenantId(tenant);
    invoice.setDistributionPartnerId(tenant);
    invoice.setPropositionId(proposition);
    invoice.setPlanId("plan");
    invoice.setOrchestratorId("orch");
    invoice.setOrchestratorVersion(2);
    invoice.setStatus(TransactionStatus.ACCEPTED);
    invoice.setReceivedAt(Instant.parse("2026-11-20T03:00:00Z"));
    invoice.setTransactionTime(Instant.parse("2020-01-01T00:00:00Z"));
    invoice.setCurrency(currency);
    invoice.setTotalAmount(bd("140"));
    invoice.setItems(List.of(new InvoiceItemDto("A", "A", bd("5"), bd("10"), bd("50")),
        new InvoiceItemDto("B", "B", bd("9"), bd("10"), bd("90"))));
    invoice.setSubmissionId(id + ":INVOICE_PAID");
    invoice.setSnapshot(snapshot(invoice, bd(net), costing(currency, "10", "2", "3", "4", "5"),
        bd("10"), tenant, net == null ? List.of("NET_REVENUE_UNVERIFIED") : List.of()));
    refreshHandoff(invoice);
    return invoice;
  }

  static void refreshHandoff(Invoice invoice) {
    invoice.setSubmissionHandoff(new Submission(invoice.getSubmissionId(), "INVOICE_PAID",
        invoice.getReceivedAt(), invoice.getTenantId(), "INVOICE", invoice.getId(),
        invoice.getPropositionId(), invoice.getPlanId(), invoice.getOrchestratorId(),
        invoice.getOrchestratorVersion()));
    invoice.setSubmissionPending(true);
  }

  static InvoiceSnapshot.Costing costing(String currency, String ingredient, String packaging,
      String labor, String operation, String marketing) {
    return new InvoiceSnapshot.Costing("pinned-attribute", "pinned-process", 7L, currency,
        bd(ingredient), bd(packaging), bd(labor), bd(operation), bd(marketing),
        bd("99"), bd("77"), bd("99999"), bd("99999"), bd("99999"));
  }

  static InvoiceSnapshot snapshot(Invoice invoice, BigDecimal net, InvoiceSnapshot.Costing costing,
      BigDecimal rate, String tenant, List<String> missing) {
    return new InvoiceSnapshot(1, invoice.getPropositionId(), 8L, tenant, tenant,
        invoice.getPlanId(), 3, invoice.getOrchestratorId(), invoice.getOrchestratorVersion(),
        "pinned-product", "pinned-process", 2, 7L, costing,
        new InvoiceSnapshot.ProfitShare(bd("60"), rate, bd("30")), null,
        invoice.getCurrency(), invoice.getReceivedAt(), "Asia/Ho_Chi_Minh", 11, 2026,
        missing.isEmpty() ? "READY" : "MISSING_DATA", missing, net,
        bd("123456"), bd("123456")); // A calculator must not blindly trust precomputed money.
  }
}
