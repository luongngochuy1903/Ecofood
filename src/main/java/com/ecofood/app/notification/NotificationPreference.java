package com.ecofood.app.notification;

import com.ecofood.core.entity.BaseEntity;
import com.ecofood.app.notification.NotificationContracts.ConsentChange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

@Getter @Setter
@Document("notification_preferences")
public class NotificationPreference extends BaseEntity {
  @Field("customer_identifier") private String customerId;
  @Field("customer_tenant_identifier") private String customerTenantId;
  @Version private Long version;
  private boolean serviceInAppEnabled = true;
  private boolean transactionalEmailEnabled = true;
  private boolean marketingInAppEnabled;
  private boolean marketingEmailEnabled;
  private boolean marketingConsent;
  private Instant marketingConsentAt;
  private String marketingConsentSource;
  private Instant marketingConsentWithdrawnAt;
  private List<ConsentChange> consentHistory = new ArrayList<>();
}
