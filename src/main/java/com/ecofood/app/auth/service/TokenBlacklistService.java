package com.ecofood.app.auth.service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory jti blacklist — the dummy-app equivalent of DCMP's real Redis
 * CacheTokenService blocklist. Deliberately NOT distributed/persistent:
 * fine for a single-instance app, but a real multi-instance deployment
 * needs a shared store (Redis) since this map only lives in one JVM's
 * memory and is lost on restart.
 */
@Service
public class TokenBlacklistService {

  private final ConcurrentHashMap<String, Long> revokedJtiExpiry = new ConcurrentHashMap<>();

  public void revoke(String jti, long expiryEpochSeconds) {
    revokedJtiExpiry.put(jti, expiryEpochSeconds);
  }

  public boolean isRevoked(String jti) {
    Long expiry = revokedJtiExpiry.get(jti);
    if (expiry == null) {
      return false;
    }
    if (expiry < Instant.now().getEpochSecond()) {
      // The token would already be rejected on expiry alone — no need to
      // keep it in the map forever, so clean up lazily on read.
      revokedJtiExpiry.remove(jti);
      return false;
    }
    return true;
  }
}
