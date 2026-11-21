package com.ecofood.app.notification.delivery;

import com.ecofood.app.notification.entity.Notification;
import com.ecofood.app.notification.exception.DeliveryFailure;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import java.net.ConnectException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.*;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SmtpNotificationSender implements NotificationEmailSender {
  private final ObjectProvider<JavaMailSender> provider;
  public void send(Notification notification) {
    JavaMailSender sender = provider.getIfAvailable();
    if (sender == null) throw new DeliveryFailure("SMTP_NOT_CONFIGURED", true, false);
    try {
      MimeMessage message = sender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
      helper.setFrom(notification.getSendFrom()); helper.setTo(notification.getSendTo());
      helper.setSubject(notification.getSubject()); helper.setText(notification.getContent(), false);
      // Stable identity is useful for tracing, but SMTP does not promise idempotent delivery.
      message.setHeader("X-EcoFood-Notification-Id", notification.getId());
      sender.send(message);
    } catch (MailAuthenticationException e) { throw new DeliveryFailure("SMTP_AUTH_FAILED", false, false); }
    catch (MessagingException | MailPreparationException e) { throw new DeliveryFailure("EMAIL_INVALID", false, false); }
    catch (MailException e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause()) {
        if (cause instanceof ConnectException || cause.getClass().getSimpleName().equals("MailConnectException"))
          throw new DeliveryFailure("SMTP_CONNECT_FAILED", true, false);
      }
      Integer rejection = rejectionCode(e);
      if (rejection != null) throw new DeliveryFailure(rejection < 500 ? "SMTP_TEMPORARY_REJECTION" : "SMTP_REJECTED", rejection < 500, false);
      // A transport error after DATA may mean the server already accepted the email.
      throw new DeliveryFailure("SMTP_OUTCOME_UNCERTAIN", false, true);
    }
  }
  /** SMTP implementations expose rejection codes, while Jakarta Mail's base API does not. */
  private Integer rejectionCode(Throwable failure) {
    Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    java.util.ArrayDeque<Throwable> queue = new java.util.ArrayDeque<>(); queue.add(failure);
    Integer rejection = null;
    while (!queue.isEmpty()) {
      Throwable current = queue.remove(); if (!visited.add(current)) continue;
      if (current instanceof SendFailedException send && send.getValidSentAddresses() != null && send.getValidSentAddresses().length > 0) return null;
      String name = current.getClass().getName();
      if (name.startsWith("org.eclipse.angus.mail.smtp.") || name.startsWith("com.sun.mail.smtp.")) {
        try {
          Object value = current.getClass().getMethod("getReturnCode").invoke(current);
          if (value instanceof Integer code && code >= 400 && code <= 599) rejection = code;
        } catch (ReflectiveOperationException ignored) { /* Unknown provider outcome remains uncertain. */ }
      }
      if (current.getCause() != null) queue.add(current.getCause());
      if (current instanceof MessagingException mail && mail.getNextException() != null) queue.add(mail.getNextException());
      if (current instanceof MailSendException mail) queue.addAll(mail.getFailedMessages().values());
    }
    return rejection;
  }
}
