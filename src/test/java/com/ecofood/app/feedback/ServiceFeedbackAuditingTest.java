package com.ecofood.app.feedback;

import com.ecofood.app.feedback.entity.ServiceFeedback;
import com.ecofood.core.config.MongoAuditingConfig;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.IsNewAwareAuditingHandler;
import org.springframework.data.mapping.context.PersistentEntities;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

class ServiceFeedbackAuditingTest {
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @Test void baseEntityAuditRecordsCreationTimeWithoutGuestCustomerOrJwtIdentity() throws Exception {
    var context = new MongoMappingContext();
    context.setInitialEntitySet(Set.of(ServiceFeedback.class));
    context.afterPropertiesSet();
    var auditing = new IsNewAwareAuditingHandler(PersistentEntities.of(context));
    auditing.setAuditorAware(new MongoAuditingConfig().auditorAware());
    Instant now = Instant.parse("2026-11-14T00:00:00Z");
    auditing.setDateTimeProvider(() -> Optional.of(now));
    SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
        "test-only", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    var feedback = new ServiceFeedback();
    feedback.setTenantId("tenant"); feedback.setInvoiceId("invoice");
    auditing.markAudited(feedback);
    assertEquals(now, feedback.getCreatedAt());
    assertNull(feedback.getCreatedBy());
    assertEquals("tenant", feedback.getTenantId());
    assertEquals("invoice", feedback.getInvoiceId());
  }
}
