package com.ecofood.app.customer;

import com.ecofood.app.userACL.entity.UserEntity;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Document;

/** Customer specialization of the same app_user identity, never a separate Customer collection. */
@Getter
@Setter
@Document("app_user")
public class CustomerUser extends UserEntity {
  private Long customerProfileRevision;
  private String customerIdentityIssuer;
  private java.time.Instant customerEmailVerifiedAt;
  private String customerVerifiedEmailHash;
}
