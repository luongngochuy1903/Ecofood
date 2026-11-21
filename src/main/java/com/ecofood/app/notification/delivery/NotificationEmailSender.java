package com.ecofood.app.notification.delivery;

import com.ecofood.app.notification.entity.Notification;

public interface NotificationEmailSender {
  void send(Notification notification);
}
