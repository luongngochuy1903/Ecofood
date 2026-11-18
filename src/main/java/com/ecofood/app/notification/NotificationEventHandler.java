package com.ecofood.app.notification;

import com.ecofood.app.notification.NotificationContracts.Channel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Replay-safe handler: both channels use the immutable business event ID. */
@Component @RequiredArgsConstructor
public class NotificationEventHandler {
  private final NotificationService notifications;
  public void handle(NotificationOutboxEvent event) {
    notifications.create(event.channel(Channel.IN_APP));
    notifications.create(event.channel(Channel.EMAIL));
  }
}
