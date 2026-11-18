package com.ecofood.app.complaint;

import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ComplaintAccessSecrets {
  @Value("${ecofood.complaints.access-secret:${ecofood.receipts.token-secret:}}") private String secret;
  public String derive(String purpose, String... values) {
    try {
      byte[] master = Base64.getDecoder().decode(secret); if (master.length < 32) throw new IllegalArgumentException();
      var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(master, "HmacSHA256"));
      byte[] key = mac.doFinal(("ecofood:complaint:" + purpose + ":v1").getBytes(StandardCharsets.UTF_8));
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(ComplaintCommandIdentity.hash((Object[]) values).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Complaint access signing is unavailable"); }
  }
}
