package com.ecofood.app.transaction.config;

import com.ecofood.core.dto.SaleOrchestratorConfig;
import com.ecofood.core.service.SaleOrchestratorConfigProvider;
import java.util.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** No default bindings. All simulated UC-014 data must be supplied externally. */
@Data
@Component
@Profile("(dev | test) & !prod")
@ConfigurationProperties(prefix = "ecofood.transactions.mock")
public class MockSaleOrchestratorConfigProvider implements SaleOrchestratorConfigProvider {
  private List<Binding> bindings = new ArrayList<>();

  @Data
  public static class Binding {
    private String subject;
    private SaleOrchestratorConfig config;
  }

  @Override
  public Optional<SaleOrchestratorConfig> findForPos(String tenantId, String subject) {
    var matches = bindings.stream().filter(b -> Objects.equals(subject, b.subject))
        .map(Binding::getConfig).filter(Objects::nonNull)
        .filter(c -> Objects.equals(tenantId, c.tenantId())).toList();
    if (matches.size() > 1) throw new IllegalStateException("Ambiguous POS configuration");
    return matches.stream().findFirst();
  }
}
