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

  @Test
  void isIssuedBeforeInvalidationFalseForAUserNeverInvalidated() {
    assertThat(tokenBlacklistService.isIssuedBeforeInvalidation("user-1", Instant.now().getEpochSecond())).isFalse();
  }

  @Test
  void isIssuedBeforeInvalidationTrueForATokenIssuedBeforeTheRecordedLogout() {
    String userId = "user-2";
    long issuedAt = Instant.now().getEpochSecond();
    tokenBlacklistService.invalidateAllForUser(userId);

    assertThat(tokenBlacklistService.isIssuedBeforeInvalidation(userId, issuedAt - 5)).isTrue();
  }

  @Test
  void isIssuedBeforeInvalidationFalseForATokenIssuedAtOrAfterTheRecordedLogout() {
    String userId = "user-3";
    tokenBlacklistService.invalidateAllForUser(userId);
    long afterLogout = Instant.now().getEpochSecond() + 5;

    assertThat(tokenBlacklistService.isIssuedBeforeInvalidation(userId, afterLogout)).isFalse();
  }

  @Test
  void invalidateAllForUserIsANoOpForNullUserId() {
    tokenBlacklistService.invalidateAllForUser(null);

    assertThat(tokenBlacklistService.isIssuedBeforeInvalidation(null, Instant.now().getEpochSecond())).isFalse();
  }
}
