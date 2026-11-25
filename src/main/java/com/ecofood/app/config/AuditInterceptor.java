package com.ecofood.app.config;

import com.ecofood.core.dto.AuditLogDtos.Event;
import com.ecofood.core.enums.AuditOutcome;
import com.ecofood.core.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuditInterceptor implements HandlerInterceptor {
  public static final String BUSINESS_FAILURE = AuditInterceptor.class.getName() + ".businessFailure";
  private final AuditLogService audit;

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    if (!(handler instanceof HandlerMethod method)) return;
    Audited annotation = method.getMethodAnnotation(Audited.class);
    String actor = MDC.get("actorId");
    String tenant = MDC.get("tenantId");
    if (annotation == null || actor == null || tenant == null) return;

    boolean failed = ex != null || response.getStatus() >= 400 || Boolean.TRUE.equals(request.getAttribute(BUSINESS_FAILURE));
    var outcome = failed ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS;
    var logger = LoggerFactory.getLogger(method.getBeanType());
    var event = new Event(actor, tenant, annotation.value(), method.getBeanType().getSimpleName(),
        Instant.now(), outcome, MDC.get("requestId"));
    if (outcome == AuditOutcome.SUCCESS) logger.info("action={} outcome={}", annotation.value(), outcome);
    else logger.warn("action={} outcome={}", annotation.value(), outcome);
    try {
      audit.record(event);
    } catch (RuntimeException writeError) {
      logger.error("Unable to persist audit event action={} outcome={}", annotation.value(), outcome, writeError);
    }
  }
}
