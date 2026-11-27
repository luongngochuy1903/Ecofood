package com.ecofood.app.notification.service;

import com.ecofood.app.tenant.entity.TenantEntity;
import java.net.URI;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Reads existing tenant branding only; never changes tenant resolution or authentication. */
@Component
public class ComplaintEmailBranding {
  private final MongoTemplate mongo;
  private final String scheme;
  public ComplaintEmailBranding(MongoTemplate mongo, @Value("${app.tenant.redirect-scheme:https}") String scheme) { this.mongo = mongo; this.scheme = scheme; }
  public record Brand(String name, String logo, String origin) {}
  public Brand resolve(String tenantId) {
    TenantEntity tenant = tenantId != null && ObjectId.isValid(tenantId) ? mongo.findById(new ObjectId(tenantId), TenantEntity.class) : null;
    if (tenant == null || Boolean.TRUE.equals(tenant.getDeleted())) return new Brand("EcoFood", null, null);
    String name = tenant.getTenantName();
    return new Brand(name == null || name.isBlank() ? "EcoFood" : name, safeLogo(tenant.getLogoUrl()), origin(tenant.getDomainUrl()));
  }
  String origin(String domain) {
    if (domain == null || domain.isBlank() || domain.startsWith("//") || domain.contains("\\")) return null;
    try {
      URI uri = URI.create(domain.contains("://") ? domain : scheme + "://" + domain);
      if (!safe(uri) || uri.getQuery() != null || uri.getFragment() != null) return null;
      // Sub-tenant suffixes are tenant identities; the existing SPA routes live at the origin root.
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null).toASCIIString();
    } catch (Exception invalid) { return null; }
  }
  static String safeLogo(String value) {
    if (value == null || value.isBlank() || value.contains("\\")) return null;
    try { URI uri = URI.create(value); return safe(uri) && "https".equalsIgnoreCase(uri.getScheme()) && uri.getFragment() == null ? uri.toASCIIString() : null; }
    catch (IllegalArgumentException invalid) { return null; }
  }
  private static boolean safe(URI uri) {
    return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null && uri.getPort() <= 65535;
  }
}
