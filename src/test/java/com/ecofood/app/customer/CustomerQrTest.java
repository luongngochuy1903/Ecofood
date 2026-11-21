package com.ecofood.app.customer;

import static com.ecofood.app.customer.CustomerFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.ecofood.app.customer.config.CustomerSettings;
import com.ecofood.app.customer.security.CustomerReceiptTokens;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class CustomerQrTest {
  @Test void actualQrPngDecodesToExactlyTheCapabilityUrl() throws Exception {
    var tokens = new CustomerReceiptTokens(settings()); String token = tokens.token(tokens.id(TENANT, SELLER, INVOICE, "request"), java.time.Instant.now()), url = tokens.url(token);
    var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(tokens.qr(url))));
    var source = new RGBLuminanceSource(image.getWidth(), image.getHeight(), image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
    assertEquals(url, new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText()); assertFalse(url.contains(INVOICE));
  }
  @Test void insecureOriginAndWeakSigningKeyFailClosed() {
    var weak = new CustomerSettings(TENANT, "short", "http://untrusted.example.org", 24);
    assertThrows(org.springframework.web.server.ResponseStatusException.class, weak::receiptKey); assertThrows(org.springframework.web.server.ResponseStatusException.class, weak::base);
    assertDoesNotThrow(() -> new CustomerSettings("", "", "http://localhost:5173", 24).base());
  }
}
