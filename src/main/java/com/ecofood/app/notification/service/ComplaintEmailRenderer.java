package com.ecofood.app.notification.service;

import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.submission.entity.Submission;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Complaint presentation only. No event creation, recipient selection or delivery decisions. */
@Component
@RequiredArgsConstructor
public class ComplaintEmailRenderer {
  private final ComplaintEmailBranding branding;
  private final NotificationDirectory directory;
  private static final Pattern TOKEN = Pattern.compile("\\{\\{([A-Za-z]+)}}");
  private static final Map<String, Definition> TEMPLATES = Map.of(
      "COMPLAINT_CREATED", new Definition("complaint-submitted", "New Complaint Submitted", "A new complaint requires your attention.", "Review Complaint", "Complaint Description", "SUBMITTED", true),
      "COMPLAINT_RESOLVED", new Definition("complaint-resolved", "Complaint Resolved", "Our support team has resolved your complaint.", "View Resolution", "Resolution Details", "RESOLVED", false));
  private record Definition(String resource, String title, String subtitle, String action, String contentHeading, String status, boolean admin) {}
  public record Email(String subject, String html) {}
  public static boolean supports(String event) { return TEMPLATES.containsKey(event); }

  public Email render(Submission event, Map<String,String> values, NotificationRecipients.Recipient recipient) {
    var definition = TEMPLATES.get(event.event());
    if (definition == null) throw new IllegalArgumentException("Unsupported complaint email event");
    var brand = branding.resolve(event.tenantId());
    String portalTenant = definition.admin() ? event.tenantId() : values.get("customerTenantId");
    var portal = definition.admin() ? brand : branding.resolve(portalTenant);
    var user = recipient.userId() == null || portalTenant == null ? null : directory.user(recipient.userId(), portalTenant);
    String name = user == null ? "" : (Objects.toString(user.getFirstName(), "") + " " + Objects.toString(user.getLastName(), "")).trim();
    String id = values.get("complaintId");
    if (id == null || id.isBlank()) throw new IllegalStateException("TEMPLATE_DATA_MISSING");
    Map<String,String> slots = new HashMap<>();
    slots.put("title", h(definition.title())); slots.put("subtitle", h(definition.subtitle()));
    slots.put("greeting", h("Dear " + (!name.isBlank() ? name : definition.admin() ? "Support Team" : "Customer") + ","));
    slots.put("brandName", h(brand.name()));
    String logo = brand.logo() == null ? "" : "<img src=\"" + h(brand.logo()) + "\" alt=\"" + h(brand.name()) + "\" width=\"120\" style=\"display:block;max-width:120px;height:auto;margin:0 auto 12px;\">";
    slots.put("header", fragment("fragments/email-header", Map.of("logo",logo,"brandName",h(brand.name()))));
    slots.put("footer", fragment("fragments/email-footer", Map.of("year",String.valueOf(Year.now(ZoneOffset.UTC).getValue()))));
    slots.put("statusLabel", definition.admin() ? "Submitted" : "Resolved");
    slots.put("statusColor", definition.admin() ? "#2563EB" : "#16A34A");
    slots.put("statusBackground", definition.admin() ? "#EFF6FF" : "#F0FDF4");
    StringBuilder rows = new StringBuilder();
    row(rows,"Complaint ID",id); row(rows,"Subject",values.get("complaintSubject"));
    row(rows,"Complaint Type", "TENANT".equals(values.get("complaintType")) ? "Service" : "ECOSYSTEM".equals(values.get("complaintType")) ? "App Platform" : null);
    if (definition.admin()) { row(rows,"Customer Name",values.get("customerName")); row(rows,"Customer Email",values.get("customerEmail")); row(rows,"Tenant",brand.name()); }
    if ("TENANT".equals(values.get("complaintType"))) row(rows,"Invoice ID",values.get("invoiceId"));
    row(rows,definition.admin() ? "Submitted At" : "Resolved At", date(event.createdAt()));
    rows.append(fragment("fragments/information-row", Map.of("label","Status", "value",fragment("fragments/status-badge", Map.of("label",slots.get("statusLabel"),"color",slots.get("statusColor"),"background",slots.get("statusBackground"))))));
    slots.put("information", fragment("fragments/complaint-information", Map.of("rows",rows.toString())));
    slots.put("introduction", fragment("complaint/"+definition.resource(), Map.of()));
    String content = values.get(definition.admin() ? "complaintDescription" : "resolution");
    slots.put("content", content == null || content.isBlank() ? "" : fragment("fragments/content-section", Map.of("heading",definition.contentHeading(),"content",h(content).replace("\r\n","\n").replace("\r","\n").replace("\n","<br>"))));
    slots.put("closing", definition.admin() ? "<p>Please access the EcoFood Management Portal to review and process this complaint.</p>" : "<p>You can review the resolution and complete complaint history through the Customer Portal.</p><p>We appreciate your feedback and thank you for helping us improve the quality of our services.</p>");
    String url = portal.origin() == null ? null : portal.origin() + (definition.admin() ? "/complaints?complaintId=" : "/customer/complaints?complaintId=") + encode(id) + (!definition.admin() && portalTenant != null ? "&tenant=" + encode(portalTenant) : "");
    slots.put("action", url == null ? "" : fragment("fragments/action-button", Map.of("label",definition.action(),"url",h(url))));
    String subject = "[EcoFood] " + (definition.admin() ? "New Complaint Submitted" : "Your Complaint Has Been Resolved") + " – " + id.replace('\r',' ').replace('\n',' ');
    return new Email(subject, fragment("layout/base-email", slots));
  }
  private void row(StringBuilder rows, String label, String value) { if (value != null && !value.isBlank()) rows.append(fragment("fragments/information-row",Map.of("label",h(label),"value",h(value)))); }
  private static String h(String value) { return EmailHtml.escape(value); }
  private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
  private static String date(Instant value) { return value == null ? null : DateTimeFormatter.ofPattern("MMM d, uuuu, HH:mm 'UTC'",Locale.ENGLISH).withZone(ZoneOffset.UTC).format(value); }
  private String fragment(String resource, Map<String,String> values) {
    try {
      String template = new ClassPathResource("templates/email/"+resource+".html").getContentAsString(StandardCharsets.UTF_8);
      Matcher matcher = TOKEN.matcher(template); StringBuilder result = new StringBuilder();
      while (matcher.find()) {
        String replacement = values.get(matcher.group(1));
        if (replacement == null) throw new IllegalStateException("TEMPLATE_DATA_MISSING");
        matcher.appendReplacement(result,Matcher.quoteReplacement(replacement));
      }
      return matcher.appendTail(result).toString();
    } catch (java.io.IOException missing) { throw new IllegalStateException("Email template unavailable",missing); }
  }
}
