package com.ecofood.app.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.List;
import javax.crypto.SecretKey;
import org.bson.types.ObjectId;

/**
 * Matches DCMP's real Service_Core AuthenticationContext exactly (confirmed
 * by reading that class directly) — a static-ThreadLocal, JWT-parsing
 * accessor for "who is making this request," independent of Spring
 * Security's SecurityContextHolder/Authentication. InternalJwtRequestFilter
 * populates it (setToken/setSecret) once per request and clears it once the
 * request completes, since these are plain ThreadLocals that would
 * otherwise leak across pooled threads.
 *
 * DCMP's real unload() is an instance method despite every field it touches
 * being static, while the constructor is private — a contradiction in the
 * real source that makes unload() uncallable from outside the class as
 * written. Rather than replicate an uncallable method, unload() is declared
 * static here so callers (InternalJwtRequestFilter) can actually clear the
 * ThreadLocals — a deliberate deviation from the literal DCMP source, not a
 * fidelity slip.
 */
public class AuthenticationContext {

  private AuthenticationContext() {}

  private static final ThreadLocal<String> token = new ThreadLocal<String>();
  private static final ThreadLocal<String> secret = new ThreadLocal<String>();
  private static final ThreadLocal<String> clientIpAddress = new ThreadLocal<>();

  public static String getToken() {
    if (token.get() == null) {
      return null;
    }
    return token.get().replace("Bearer ", "");
  }

  public static String getFullToken() {
    return token.get();
  }

  public static void setToken(String authToken) {
    token.set(authToken);
  }

  public static void setSecret(String authSecret) {
    secret.set(authSecret);
  }

  public static ObjectId getTenantId() {
    return new ObjectId((String) getClaims().get("tenantId"));
  }

  public static String getClientId() {
    return getClaims().get("clientId").toString();
  }

  public static String getTenantCode() {
    return getClaims().get("tenantCode").toString();
  }

  public static String getUserCode() {
    return getClaims().get("code").toString();
  }

  public static ObjectId getUserId() {
    return new ObjectId((String) getClaims().get("sub"));
  }

  public static String getUserName() {
    return getClaims().get("email", String.class);
  }

  public static List<String> getRoleGroups() {
    return getClaims().get("roleGroups", List.class);
  }

  public static List<String> getPermissions() {
    return getClaims().get("permissions", List.class);
  }

  public static List<String> getRoles() {
    return getClaims().get("roles", List.class);
  }

  public static Claims getClaims() {
    byte[] keyBytes = Decoders.BASE64.decode(secret.get());
    SecretKey secretKey = Keys.hmacShaKeyFor(keyBytes);
    return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(getToken()).getPayload();
  }

  public static void setClientIpAddress(String clientIpAddressRequest) {
    clientIpAddress.set(clientIpAddressRequest);
  }

  public static String getClientIpAddress() {
    return clientIpAddress.get();
  }

  public static void unload() {
    token.remove();
    secret.remove();
  }
}
