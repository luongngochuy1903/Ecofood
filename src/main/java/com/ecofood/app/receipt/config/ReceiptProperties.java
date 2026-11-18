package com.ecofood.app.receipt.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ecofood.receipts")
public class ReceiptProperties {
  private String tokenSecret = "";
  private String publicBaseUrl = "";
  private Duration ttl = Duration.ofHours(24);
}
