package com.ecofood.app.architecture;

import static org.junit.jupiter.api.Assertions.*;

import com.ecofood.app.catalog.entity.ApiPublication;
import com.ecofood.app.complaint.entity.Complaint;
import com.ecofood.app.customer.entity.CustomerInvoice;
import com.ecofood.app.customer.entity.CustomerUser;
import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.entity.NotificationRule;
import com.ecofood.app.notification.entity.SubmissionNotificationState;
import com.ecofood.app.orchestrator.entity.PlanSaleOrchestrator;
import com.ecofood.app.proposition.service.PropositionMongoConfiguration;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Set;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

class ModuleRefactorCompatibilityTest {
  static Stream<Arguments> documents() {
    return Stream.of(
        Arguments.of(new ApiPublication(), "com.ecofood.app.catalog.ApiPublication"),
        Arguments.of(new Complaint(), "com.ecofood.app.complaint.Complaint"),
        Arguments.of(new CustomerInvoice(), "com.ecofood.app.customer.CustomerInvoice"),
        Arguments.of(new CustomerUser(), "com.ecofood.app.customer.CustomerUser"),
        Arguments.of(new Notification(), "com.ecofood.app.notification.Notification"),
        Arguments.of(new NotificationRule(), "com.ecofood.app.notification.NotificationRule"),
        Arguments.of(new SubmissionNotificationState(), "com.ecofood.app.notification.SubmissionNotificationState"),
        Arguments.of(new PlanSaleOrchestrator(), "com.ecofood.app.orchestrator.PlanSaleOrchestrator"));
  }

  private MappingMongoConverter converter() {
    var conversions = new PropositionMongoConfiguration().propositionMongoConversions();
    var context = new MongoMappingContext();
    context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
    context.setInitialEntitySet(Set.of(ApiPublication.class, Complaint.class, CustomerInvoice.class,
        CustomerUser.class, Notification.class, NotificationRule.class,
        SubmissionNotificationState.class, PlanSaleOrchestrator.class));
    context.afterPropertiesSet();
    var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
    converter.setCustomConversions(conversions);
    converter.afterPropertiesSet();
    return converter;
  }

  @ParameterizedTest
  @MethodSource("documents")
  void relocatedEntitiesKeepTheExistingMongoClassIdentifiers(Object entity, String legacyName) {
    var document = new Document();
    converter().write(entity, document);
    assertEquals(legacyName, document.getString("_class"));
    var restored = converter().read(entity.getClass(), document);
    assertEquals(entity.getClass(), restored.getClass());
  }

  @Test
  void freshConverterResolvesLegacyCustomerInvoiceSubtypeBeforeAnyWrite() {
    var receipt = new Document("_id", "receipt").append("requestId", "request").append("tokenHash", "hash");
    var stored = new Document("_id", "invoice")
        .append("_class", "com.ecofood.app.customer.CustomerInvoice")
        .append("tenantId", "tenant")
        .append("customerReceipt", receipt);
    var invoice = converter().read(Invoice.class, stored);
    var customerInvoice = assertInstanceOf(CustomerInvoice.class, invoice);
    assertEquals("receipt", customerInvoice.getCustomerReceipt().id());
    assertEquals("tenant", invoice.getTenantId());
  }

  static Stream<Arguments> responses() {
    return Stream.of(Arguments.of(new Complaint(), ComplaintView.class, "handoffs"),
        Arguments.of(new Notification(), NotificationView.class, "leaseToken"),
        Arguments.of(new NotificationRule(), NotificationRuleView.class, "unrelatedField"));
  }

  @ParameterizedTest
  @MethodSource("responses")
  void coreResponseContractsPreserveEntityJsonAndHiddenFields(Object entity, Class<?> contract, String hidden) throws Exception {
    var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    var actual = mapper.readTree(mapper.writerFor(contract).writeValueAsString(entity));
    assertEquals(mapper.readTree(mapper.writeValueAsString(entity)), actual);
    assertFalse(actual.has(hidden));
  }

  @Test
  void movedEnumsKeepTheExistingWireValuesAndCase() throws Exception {
    var mapper = new ObjectMapper();
    assertEquals("\"PRODUCT\"", mapper.writeValueAsString(CatalogTypeEnum.PRODUCT));
    assertEquals("\"PUBLISHED\"", mapper.writeValueAsString(CatalogPublicationStatusEnum.PUBLISHED));
    assertEquals("\"ECOSYSTEM\"", mapper.writeValueAsString(ComplaintTypeEnum.ECOSYSTEM));
    assertEquals("\"WAITING_REPORTER\"", mapper.writeValueAsString(ComplaintStatusEnum.WAITING_REPORTER));
    assertEquals("\"QUARTERLY\"", mapper.writeValueAsString(DashboardPeriodEnum.QUARTERLY));
    assertEquals("\"Functional\"", mapper.writeValueAsString(RoleType.Functional));
  }
}
