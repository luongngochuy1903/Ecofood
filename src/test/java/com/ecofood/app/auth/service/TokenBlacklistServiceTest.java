package com.ecofood.app.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TokenBlacklistServiceTest {

  private final TokenBlacklistService tokenBlacklistService = new TokenBlacklistService();

  @Test
  void isRevokedFalseForAJtiNeverRevoked() {
    assertThat(tokenBlacklistService.isRevoked("never-seen")).isFalse();
  }

  @Test
  void isRevokedTrueRightAfterRevokingWithAFutureExpiry() {
    String jti = "jti-1";
    tokenBlacklistService.revoke(jti, Instant.now().getEpochSecond() + 3600);

    assertThat(tokenBlacklistService.isRevoked(jti)).isTrue();
  }

  @Test
  void isRevokedFalseOnceTheEntryHasNaturallyExpired() {
    String jti = "jti-2";
    tokenBlacklistService.revoke(jti, Instant.now().getEpochSecond() - 10);

    assertThat(tokenBlacklistService.isRevoked(jti)).isFalse();
  }

  @Test
  void expiredEntryIsLazilyRemovedAndStaysFalseOnSubsequentChecks() {
    String jti = "jti-3";
    tokenBlacklistService.revoke(jti, Instant.now().getEpochSecond() - 10);

    tokenBlacklistService.isRevoked(jti);
    assertThat(tokenBlacklistService.isRevoked(jti)).isFalse();
  }
}
