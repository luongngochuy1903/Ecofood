package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComplaintProcessingCatalogExampleTest {
  @Test void documentedStandaloneCatalogRoutesInvoiceToDpAndPlatformToSupportWithValidEscalationEdges() throws Exception {
    var json = Files.readString(Path.of("config/complaint-routing-task4.example.json"))
        .replace("<SELLER_TENANT_ID>", ComplaintFixture.T).replace("<PLATFORM_ECO_TENANT_ID>", ComplaintFixture.P)
        .replace("<PLATFORM_ROUTING_ADMIN_USER_ID>", "platform-handler").replace("<SELLER_HANDLER_USER_ID>", "handler")
        .replace("<SELLER_FINAL_AUTHORITY_USER_ID>", "authority").replace("<ACTUAL_INVOICE_DISTRIBUTION_PARTNER_ID>", "SHOP-A");
    var config = new ObjectMapper().registerModule(new JavaTimeModule()).readValue(json, ComplaintRoutingCatalog.Configuration.class);
    var f = new ComplaintFixture(); f.categories.clear(); f.categories.addAll(config.categories()); f.queues.clear(); f.members.clear(); f.policies.clear();
    for (var queue : config.queues()) f.queues.put(queue.id(), queue);
    for (var member : config.memberships()) { f.members.computeIfAbsent(member.queueId(), key -> new ArrayList<>()).add(member); f.grant(member.userId(), f.queues.get(member.queueId()).scope().tenantId()); }
    f.policies.addAll(config.policies()); config.policies().forEach(f.validator::validate);
    assertEquals("seller-support", f.resolver.invoice(new VerifiedInvoice("invoice", ComplaintFixture.T, "SHOP-A"), f.guest(), "PRODUCT_ISSUE").queueId());
    assertEquals("platform-review", f.resolver.platform(f.customer("customer"), "SYSTEM_ERROR").queueId());
    assertTrue(config.policies().stream().noneMatch(p -> p.kind() == Kind.INTERNAL));
  }
}
