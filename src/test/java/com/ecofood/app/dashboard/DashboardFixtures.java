package com.ecofood.app.dashboard;

import com.ecofood.app.metrics.entity.ActualMetrics;
import com.ecofood.app.proposition.entity.PropositionEntity;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.bson.types.ObjectId;

final class DashboardFixtures {
  static final String A = "111111111111111111111111", B = "222222222222222222222222", OWNER = "333333333333333333333333", PROP = "444444444444444444444444", PRODUCT = "555555555555555555555555", ENTERPRISE = "666666666666666666666666", USER = "777777777777777777777777";
  static BigDecimal bd(String value) { return value == null ? null : new BigDecimal(value); }
  static EconomicMetricsRequest quarter(int quarter, String value) { return new EconomicMetricsRequest(null, 2026, bd(value), bd(value), bd(value), bd(value), quarter, null); }
  static EconomicMetricsRequest month(int month, String value) { return new EconomicMetricsRequest(month, 2026, bd(value), bd(value), bd(value), bd(value)); }
  static ActualMetrics actual(String tenant, int month, String currency, long volume, String profit) {
    return new ActualMetrics(tenant + month + currency, PROP, tenant, 2026, month, currency, volume,
        bd("2"), bd("3"), bd(profit), new ActualMetrics.KnownTotals(bd("2"), bd("3"), bd(profit)),
        new ActualMetrics.Coverage(volume, volume, profit == null ? 0 : volume, profit == null ? volume : 0), "v1", 1, Instant.parse("2026-01-02T00:00:00Z"));
  }
  static PropositionEntity proposition() {
    var p = new PropositionEntity(); p.setId(new ObjectId(PROP)); p.setEnterpriseId(new ObjectId(ENTERPRISE)); p.setOwningTenantId(new ObjectId(OWNER)); p.setProductId(new ObjectId(PRODUCT));
    p.setDefinition(new PropositionDefinition("Test proposition", null, List.of(), null, List.of(A, B), "USD", null, null, null,
        List.of(quarter(1, "100")), List.of(new PartnerMetricsRequest(A, null, List.of(quarter(1, "30"))), new PartnerMetricsRequest(B, null, List.of(quarter(1, "70")))),
        null, null, null, Set.of())); return p;
  }
  static PropositionTenantReference tenant(String id, boolean enterprise) { return new PropositionTenantReference(id, "Tenant " + id, enterprise ? ENTERPRISE : null, enterprise ? PartnerTypeEnum.ENTERPRISE : PartnerTypeEnum.BRAND, TenantTypeEnum.STANDALONE, enterprise ? List.of(OWNER, A, B) : List.of(id), List.of(), false); }
  static DashboardAccess.Actor actor(boolean enterprise) { return new DashboardAccess.Actor(USER, tenant(enterprise ? OWNER : A, enterprise), enterprise ? Set.of(OWNER, A, B) : Set.of(A), enterprise, null); }
  static DashboardDtos.Selection selection(DashboardDtos.Period period) { return new DashboardDtos.Selection(A, PROP, "USD", 2026, period, 1, 1, false); }
}
