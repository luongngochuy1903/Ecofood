package com.ecofood.app.notification;

import static com.ecofood.app.notification.NotificationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.net.ConnectException;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;

class NotificationSmtpTest {
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
}
