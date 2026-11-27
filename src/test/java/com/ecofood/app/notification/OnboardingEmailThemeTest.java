package com.ecofood.app.notification;

import static org.junit.jupiter.api.Assertions.*;
import freemarker.template.*;
import java.io.StringWriter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class OnboardingEmailThemeTest {
  @Test void verificationThemePreservesSignedLinkExpiryAndEscapesUserData() throws Exception {
    var config=new Configuration(Configuration.VERSION_2_3_32);
    config.setDirectoryForTemplateLoading(Path.of("keycloak/themes/ecofood/email/html").toFile());
    config.setDefaultEncoding("UTF-8"); config.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
    String link="https://identity.example.org/realms/tenant/login-actions/action-token?key=SIGNED_TOKEN&client_id=existing-client";
    var data=new HashMap<String,Object>();
    data.put("user",Map.of("firstName","<script>unsafe</script>","username","name & Unicode ễ"));
    data.put("realmName","Tenant & Brand"); data.put("link",link); data.put("linkExpiration",30);
    data.put("linkExpirationFormatter",(TemplateMethodModelEx)args -> "30 minutes");
    var writer=new StringWriter(); config.getTemplate("email-verification.ftl").process(data,writer);
    String html=writer.toString();
    assertFalse(html.contains("<script>")); assertTrue(html.contains("&lt;script&gt;"));
    assertTrue(html.contains("Tenant &amp; Brand")); assertTrue(html.contains("30 minutes"));
    assertTrue(html.contains("action-token?key=SIGNED_TOKEN&amp;client_id=existing-client"));
    assertFalse(html.contains("${")); assertTrue(html.contains("#075ACD"));
    Files.createDirectories(Path.of("target/email-previews")); Files.writeString(Path.of("target/email-previews/onboarding.html"),html);
    data.put("user",Map.of()); writer=new StringWriter(); config.getTemplate("email-verification.ftl").process(data,writer);
    assertTrue(writer.toString().contains("Dear there,"));
  }
}
