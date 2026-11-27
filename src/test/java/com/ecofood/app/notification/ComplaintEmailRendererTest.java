package com.ecofood.app.notification;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.notification.integration.NotificationDirectory;
import com.ecofood.app.notification.service.*;
import com.ecofood.app.submission.entity.Submission;
import com.ecofood.app.tenant.entity.TenantEntity;
import com.ecofood.app.userACL.entity.UserEntity;
import java.time.Instant;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;

class ComplaintEmailRendererTest {
  static final String QUEUE="123456789012345678901234", CUSTOMER="223456789012345678901234";
  ComplaintEmailBranding branding; NotificationDirectory directory; ComplaintEmailRenderer renderer;
  Map<String,String> values;
  @BeforeEach void setup() {
    branding=mock(ComplaintEmailBranding.class); directory=mock(NotificationDirectory.class);
    when(branding.resolve(QUEUE)).thenReturn(new ComplaintEmailBranding.Brand("Partner & Brand", "https://assets.example.org/logo.png", "https://partner.example.org"));
    when(branding.resolve(CUSTOMER)).thenReturn(new ComplaintEmailBranding.Brand("Customer Tenant",null,"https://customer.example.org"));
    renderer=new ComplaintEmailRenderer(branding,directory);
    values=new HashMap<>(Map.of("complaintId","CMP-1","complaintSubject","My order","complaintType","TENANT","invoiceId","INV-1","customerTenantId",CUSTOMER,"customerName","Customer Name","customerEmail","customer@example.org","complaintDescription","Food arrived late","resolution","We have resolved your complaint."));
  }
  Submission event(String type) { return new Submission("internal-submission",type,Instant.parse("2026-11-27T00:00:00Z"),QUEUE,"COMPLAINT","CMP-1",null,null,null,0); }
  ComplaintEmailRenderer.Email render(String type) { return renderer.render(event(type),values,new NotificationRecipients.Recipient("recipient@example.org","recipient")); }
  @Test void adminTemplateUsesQueueBrandAndExistingAdminRoute() throws Exception {
    var user=new UserEntity(); user.setFirstName("Admin"); when(directory.user("recipient",QUEUE)).thenReturn(user);
    var email=render("COMPLAINT_CREATED");
    assertEquals("[EcoFood] New Complaint Submitted – CMP-1",email.subject());
    assertTrue(email.html().contains("Dear Admin,")); assertTrue(email.html().contains("Partner &amp; Brand"));
    assertTrue(email.html().contains("https://partner.example.org/complaints?complaintId=CMP-1"));
    assertTrue(email.html().contains("customer@example.org")); assertTrue(email.html().contains("INV-1"));
    assertTrue(email.html().contains("#EFF6FF")); assertTrue(email.html().contains("Submitted"));
    assertFalse(email.html().contains("{{")); assertFalse(email.html().contains("internal-submission"));
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/email-previews"));
    java.nio.file.Files.writeString(java.nio.file.Path.of("target/email-previews/complaint-submitted.html"),email.html());
  }
  @Test void customerUsesCustomerTenantPortalAndDoesNotExposeAdministratorMetadata() throws Exception {
    values.put("complaintDescription","ADMIN_ONLY_DESCRIPTION"); values.put("customerEmail","hidden@example.org");
    var email=render("COMPLAINT_RESOLVED");
    assertTrue(email.html().contains("https://customer.example.org/customer/complaints?complaintId=CMP-1&amp;tenant="+CUSTOMER));
    assertFalse(email.html().contains("partner.example.org/customer")); assertFalse(email.html().contains("ADMIN_ONLY_DESCRIPTION"));
    assertFalse(email.html().contains("hidden@example.org")); assertFalse(email.html().contains("SMTP"));
    assertTrue(email.html().contains("Dear Customer,")); assertTrue(email.html().contains("Resolved"));
    assertTrue(email.html().contains("Nov 27, 2026, 00:00 UTC"));
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("target/email-previews"));
    java.nio.file.Files.writeString(java.nio.file.Path.of("target/email-previews/complaint-resolved.html"),email.html());
  }
  @Test void platformInvoiceAndMissingOptionalFieldsAreOmitted() {
    values.put("complaintType","ECOSYSTEM"); values.remove("customerName"); values.remove("customerEmail");
    when(branding.resolve(QUEUE)).thenReturn(new ComplaintEmailBranding.Brand("EcoFood",null,null));
    var html=render("COMPLAINT_CREATED").html();
    assertTrue(html.contains("App Platform")); assertFalse(html.contains("INV-1")); assertFalse(html.contains("Invoice ID"));
    assertFalse(html.contains("Customer Email")); assertFalse(html.contains("<img")); assertFalse(html.contains("href="));
    assertFalse(html.contains("undefined")); assertFalse(html.contains("null"));
  }
  @Test void multilineUnicodeAndHtmlAreEscapedWithoutLosingUserContent() {
    values.put("complaintSubject","<img src=x onerror=alert(1)> & \"quoted\"");
    values.put("resolution","<script>alert('x')</script>\nXin chào & cảm ơn\r\n"+"Long text ".repeat(1500));
    var html=render("COMPLAINT_RESOLVED").html();
    assertFalse(html.contains("<script>")); assertFalse(html.contains("<img src=x"));
    assertTrue(html.contains("&lt;script&gt;")); assertTrue(html.contains("<br>Xin chào &amp; cảm ơn<br>"));
    String plain=EmailHtml.plainText(html); assertTrue(plain.contains("Xin chào & cảm ơn"));
    assertTrue(plain.contains("<script>alert('x')</script>")); assertTrue(plain.contains("https://customer.example.org/customer/complaints"));
  }
  @Test void unsupportedLifecycleTemplatesDoNotBecomeNotificationEvents() {
    for(String type:List.of("COMPLAINT_CONFIRMATION","COMPLAINT_ACKNOWLEDGED","COMPLAINT_ASSIGNED","COMPLAINT_RESPONDED","COMPLAINT_INFORMATION_REQUESTED","COMPLAINT_FOLLOW_UP","COMPLAINT_CLOSED")) {
      assertFalse(ComplaintEmailRenderer.supports(type)); assertThrows(IllegalArgumentException.class,()->render(type));
    }
  }
  @Test void brandingReadsOnlyConfiguredTenantAndRejectsUnsafeUrls() {
    var mongo=mock(MongoTemplate.class); var tenant=new TenantEntity(); tenant.setTenantName("Configured Tenant");
    tenant.setDomainUrl("shop.example.org/subtenant"); tenant.setLogoUrl("javascript:alert(1)");
    when(mongo.findById(new ObjectId(QUEUE),TenantEntity.class)).thenReturn(tenant);
    var resolver=new ComplaintEmailBranding(mongo,"https");
    var brand=resolver.resolve(QUEUE); assertEquals("https://shop.example.org",brand.origin()); assertNull(brand.logo());
    tenant.setDomainUrl("https://user:password@evil.example.org"); assertNull(resolver.resolve(QUEUE).origin());
    tenant.setDomainUrl("https://shop.example.org/?accessToken=secret"); assertNull(resolver.resolve(QUEUE).origin());
    tenant.setLogoUrl("https://assets.example.org/logo.png"); assertEquals(tenant.getLogoUrl(),resolver.resolve(QUEUE).logo());
    assertEquals("EcoFood",resolver.resolve(null).name()); assertEquals("EcoFood",resolver.resolve("bad-id").name());
  }
  @Test void legacyAndOtherNotificationsRemainPlainTextEvenWhenTheyContainMarkup() {
    assertFalse(EmailHtml.supported("COMPLAINT_CREATED","<b>legacy</b>"));
    assertFalse(EmailHtml.supported("INVOICE_PAID",EmailHtml.PREFIX));
    assertTrue(EmailHtml.supported("COMPLAINT_RESOLVED",render("COMPLAINT_RESOLVED").html()));
  }
}
