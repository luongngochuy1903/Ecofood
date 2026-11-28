package com.ecofood.app.notification.entity;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Per-user read state stays separate from the delivery queue and its worker updates. */
@Document("notification_read")
public record NotificationRead(@Id String id, String tenantId, String userId, String notificationId, Instant readAt) {}
