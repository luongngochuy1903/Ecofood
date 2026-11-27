package com.ecofood.app.notification.service;

import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

/** Only our versioned complaint documents opt into HTML. Legacy messages stay plain text. */
public final class EmailHtml {
  private EmailHtml() {}
  public static final String PREFIX = "<!DOCTYPE html>\n<!-- ecofood:complaint-email:v1 -->";
  public static boolean supported(String type, String content) {
    // The matching template resource (templates/email/layout/base-email.html)
    // is plain text on disk, so its line endings follow whatever the checkout
    // used (e.g. CRLF on Windows with core.autocrlf=true) — normalize before
    // comparing so this check doesn't depend on that.
    return ("COMPLAINT_CREATED".equals(type) || "COMPLAINT_RESOLVED".equals(type))
        && content != null && content.replace("\r\n", "\n").startsWith(PREFIX);
  }
  public static String escape(String value) { return HtmlUtils.htmlEscape(value == null ? "" : value, "UTF-8"); }
  public static String plainText(String html) {
    // Strip trusted layout before decoding entities, so user text like <script> remains text.
    String body = html.replaceAll("(?is)<head\\b[^>]*>.*?</head>", "");
    var links = Pattern.compile("(?is)<a\\b[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").matcher(body);
    body = links.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(match.group(2) + " (" + match.group(1) + ")"));
    return HtmlUtils.htmlUnescape(body.replaceAll("(?is)<!--.*?-->", "").replaceAll("(?i)<br\\s*/?>|</(?:p|h[1-6]|tr|table)>", "\n")
        .replaceAll("(?i)</td>", " ").replaceAll("(?s)<[^>]*>", ""))
        .replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n[ \\t]+", "\n").replaceAll("\\n{3,}", "\n\n").trim();
  }
}
