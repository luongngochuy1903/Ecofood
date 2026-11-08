package com.ecofood.app.transaction;

import com.ecofood.app.transaction.config.MockSaleOrchestratorConfigProvider;
import com.ecofood.core.dto.SaleOrchestratorConfig;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

class MockSaleOrchestratorConfigProviderTest {
  @Test void bindsExternalConfigurationToProviderContract() {
    Map<String, String> values = new HashMap<>();
    String prefix = "ecofood.transactions.mock.bindings[0].";
    values.put(prefix + "subject", "pos-user");
    values.put(prefix + "config.tenant-id", "tenant");
    values.put(prefix + "config.pos-id", "POS1");
    values.put(prefix + "config.distribution-partner-id", "DP1");
    values.put(prefix + "config.plan-id", "PLAN1");
    values.put(prefix + "config.proposition-id", "PROP1");
    values.put(prefix + "config.active", "true");
    values.put(prefix + "config.plan-active", "true");
    values.put(prefix + "config.proposition-active", "true");
    values.put(prefix + "config.currencies[0]", "VND");
    values.put(prefix + "config.max-items", "100");
    var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(values);
    var binder = new org.springframework.boot.context.properties.bind.Binder(source);
    var provider = binder.bind("ecofood.transactions.mock",
        org.springframework.boot.context.properties.bind.Bindable.of(MockSaleOrchestratorConfigProvider.class)).get();
    var config = provider.findForPos("tenant", "pos-user").orElseThrow();
    assertEquals("DP1", config.distributionPartnerId());
    assertEquals(Set.of("VND"), config.currencies());
    assertEquals(100, config.maxItems());
    assertTrue(config.planActive());
  }
  @Test void bindingIsScopedToTenantAndSubject() {
    var provider = new MockSaleOrchestratorConfigProvider();
    var binding = new MockSaleOrchestratorConfigProvider.Binding();
    binding.setSubject("pos-user");
    binding.setConfig(new SaleOrchestratorConfig("tenant", "POS1", "DP1", "P1", "PR1", true, true, true, Set.of("VND"), 10));
    provider.setBindings(List.of(binding));
    assertTrue(provider.findForPos("tenant", "pos-user").isPresent());
    assertTrue(provider.findForPos("other", "pos-user").isEmpty());
    assertTrue(provider.findForPos("tenant", "other").isEmpty());
    provider.setBindings(List.of(binding, binding));
    assertThrows(IllegalStateException.class, () -> provider.findForPos("tenant", "pos-user"));
  }
  @Test void mockUnavailableInProductionEvenWhenDevIsActive() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("dev", "prod");
      context.register(MockSaleOrchestratorConfigProvider.class); context.refresh();
      assertTrue(context.getBeansOfType(MockSaleOrchestratorConfigProvider.class).isEmpty());
    }
  }
}
