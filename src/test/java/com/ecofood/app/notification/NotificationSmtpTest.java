package com.ecofood.app.notification;

import static com.ecofood.app.notification.NotificationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.notification.delivery.SmtpNotificationSender;
import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.exception.DeliveryFailure;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.net.ConnectException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.eclipse.angus.mail.util.MailConnectException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;

class NotificationSmtpTest {
  @Test void complaintHtmlUsesUtf8AlternativeAndRetriesReuseFrozenContent() throws Exception {
    var sender=mock(JavaMailSender.class);
    when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
    var notice=delivery(); notice.setType("COMPLAINT_RESOLVED");
    String html=com.ecofood.app.notification.service.EmailHtml.PREFIX+"\n<html><body><h1>Complaint resolved</h1><p>Xin chào &amp; thank you</p><a href=\"https://tenant.example.org/customer/complaints?complaintId=1\">View Resolution</a></body></html>";
    notice.setContent(html);
    var smtp=new SmtpNotificationSender(provider(sender)); smtp.send(notice); smtp.send(notice);
    var capture=org.mockito.ArgumentCaptor.forClass(MimeMessage.class); verify(sender,times(2)).send(capture.capture());
    for(var message:capture.getAllValues()) {
      message.saveChanges(); var parts=new java.util.HashMap<String,String>(); collectParts(message,parts);
      assertEquals(html,parts.get("text/html")); assertTrue(parts.get("text/plain").contains("Xin chào & thank you"));
      assertTrue(parts.get("text/plain").contains("https://tenant.example.org/customer/complaints?complaintId=1"));
      assertEquals(notice.getSendTo(),message.getAllRecipients()[0].toString());
    }
    assertEquals(html,notice.getContent());
  }
  private void collectParts(jakarta.mail.Part part,java.util.Map<String,String> result) throws Exception {
    if(part.isMimeType("multipart/*")) { var multi=(jakarta.mail.Multipart)part.getContent(); for(int i=0;i<multi.getCount();i++) collectParts(multi.getBodyPart(i),result); }
    else if(part.isMimeType("text/html")) result.put("text/html",(String)part.getContent());
    else if(part.isMimeType("text/plain")) result.put("text/plain",(String)part.getContent());
  }
  @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
    var provider = (ObjectProvider<JavaMailSender>) mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(sender); return provider;
  }
  @Test void sendsPlainTextUtf8ToFrozenRecipientOnly() throws Exception {
    var sender = mock(JavaMailSender.class); var message = new MimeMessage(Session.getInstance(new Properties()));
    when(sender.createMimeMessage()).thenReturn(message); var notification = delivery(); notification.setContent("<b>Không phải HTML</b>");
    new SmtpNotificationSender(provider(sender)).send(notification);
    assertEquals("customer@example.org", message.getAllRecipients()[0].toString()); assertEquals("<b>Không phải HTML</b>", message.getContent());
    assertTrue(message.isMimeType("text/plain")); assertEquals("delivery", message.getHeader("X-EcoFood-Notification-Id", null));
    verify(sender).send(message);
  }
  @Test void missingProviderAndConnectionFailureAreRetryableButAmbiguousSmtpIsNot() {
    var missingSender = new SmtpNotificationSender(provider(null));
    var missingNotice = delivery();
    var missing = assertThrows(DeliveryFailure.class, () -> missingSender.send(missingNotice));
    assertTrue(missing.retryable); assertFalse(missing.uncertain);
    var sender = mock(JavaMailSender.class); when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    doThrow(new MailSendException("connection", new ConnectException())).when(sender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
    var smtp = new SmtpNotificationSender(provider(sender));
    var connectionNotice = delivery();
    var connection = assertThrows(DeliveryFailure.class, () -> smtp.send(connectionNotice));
    assertTrue(connection.retryable); assertFalse(connection.uncertain);
    doThrow(new MailSendException("lost acknowledgment after DATA")).when(sender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
    var ambiguousNotice = delivery();
    var ambiguous = assertThrows(DeliveryFailure.class, () -> smtp.send(ambiguousNotice));
    assertFalse(ambiguous.retryable); assertTrue(ambiguous.uncertain);
  }
  @Test void providerConnectionFailureIsRetryableWithoutUncertainDelivery() {
    var sender = mock(JavaMailSender.class);
    var message = new MimeMessage(Session.getInstance(new Properties()));
    when(sender.createMimeMessage()).thenReturn(message);
    var cause = mock(MailConnectException.class);
    doThrow(new MailSendException("connection failed", cause)).when(sender).send(message);
    var smtp = new SmtpNotificationSender(provider(sender));
    var notification = delivery();

    var failure = assertThrows(DeliveryFailure.class, () -> smtp.send(notification));

    assertEquals("SMTP_CONNECT_FAILED", failure.code);
    assertTrue(failure.retryable);
    assertFalse(failure.uncertain);
  }

}
