package com.ecofood.app.common.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecofood.app.common.entity.RoleLevel;
import com.ecofood.app.common.security.AuthenticationContext;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * hasXLevelRole()/getMaxLevel() are pure reads off AuthenticationContext's
 * verified roleGroups claim (no DB call), so these tests drive that claim via
 * a real signed JWT rather than mocking AuthenticationContext (a static
 * utility class, not injectable).
 */
class ACLServiceTest {

  private static final String SECRET =
      java.util.Base64.getEncoder().encodeToString("unit-test-only-hmac-secret-0123456789".getBytes());

  private final ACLService aclService = new ACLService();

  @AfterEach
  void unload() {
    AuthenticationContext.unload();
  }

  private void authenticateWithRoleGroups(List<String> roleGroups) {
    SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    String token =
        Jwts.builder()
            .subject("000000000000000000000001")
            .claim("roleGroups", roleGroups)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(key)
            .compact();
    AuthenticationContext.setToken(token);
    AuthenticationContext.setSecret(SECRET);
  }

  @Test
  void hasSystemLevelRoleTrueWhenRoleGroupsContainsSystem() {
    authenticateWithRoleGroups(List.of("SYSTEM"));
    assertThat(aclService.hasSystemLevelRole()).isTrue();
  }

  @Test
  void hasTenantLevelRoleFalseWhenRoleGroupsDoesNotContainIt() {
    authenticateWithRoleGroups(List.of("CUSTOMER"));
    assertThat(aclService.hasTenantLevelRole()).isFalse();
  }

  @Test
  void getMaxLevelReturnsHighestPriorityMatchNotFirstInList() {
    // TENANT is listed first but SYSTEM should still win — priority order,
    // not list order.
    authenticateWithRoleGroups(List.of("TENANT", "SYSTEM"));
    assertThat(aclService.getMaxLevel()).isEqualTo(RoleLevel.SYSTEM);
  }

  @Test
  void getMaxLevelFollowsFullPriorityOrder() {
    authenticateWithRoleGroups(List.of("CUSTOMER", "MASTER_TENANT"));
    assertThat(aclService.getMaxLevel()).isEqualTo(RoleLevel.MASTER_TENANT);
  }

  @Test
  void getMaxLevelReturnsNoneWhenNoRoleGroupsMatch() {
    authenticateWithRoleGroups(List.of());
    assertThat(aclService.getMaxLevel()).isEqualTo(RoleLevel.NONE);
  }

  @Test
  void getMaxLevelReturnsNoneWhenRoleGroupsClaimAbsent() {
    authenticateWithRoleGroups(null);
    assertThat(aclService.getMaxLevel()).isEqualTo(RoleLevel.NONE);
  }
}
