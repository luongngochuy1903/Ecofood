package com.ecofood.app.customer;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CustomerReceiptTokens {
  private final CustomerSettings settings;
  String id(String tenant, String user, String invoice, String request) { return hash("ecofood.customer.receipt.id.v1\n" + tenant + "\n" + user + "\n" + invoice + "\n" + request); }
  String token(String id, java.time.Instant issuedAt) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(settings.receiptKey(), "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("ecofood.customer.receipt.token.v2\n" + id + "\n" + issuedAt.toEpochMilli()).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
  }
  static String hash(String input) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
  static void validate(String token) { if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw CustomerService.unavailable(); }
  String url(String token) { return settings.base() + "/receipt#token=" + token; }
  String qr(String url) {
    try {
      var bits = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 320, 320);
      var image = new BufferedImage(bits.getWidth(), bits.getHeight(), BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < bits.getHeight(); y++) for (int x = 0; x < bits.getWidth(); x++) image.setRGB(x, y, bits.get(x, y) ? 0x000000 : 0xffffff);
      var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return Base64.getEncoder().encodeToString(output.toByteArray());
    } catch (Exception failed) { throw CustomerSettings.unavailable("Receipt QR could not be generated"); }
  }
}
