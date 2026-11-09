/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.scope.api.CustomJwtGrantedAuthoritiesConverter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The emergency Admin grant of {@code APP_BOOTSTRAP_ADMIN_SUBS} authorises the very request that
 * earns it (REQ-SEC-088), against a real database and the real transaction boundary between the
 * login sync and the authority assembly.
 *
 * <p>Runs in the shared context: the test profile lists {@link #BOOTSTRAP_SUB} as the only
 * bootstrap subject, which no other test signs in as.
 */
@SpringBootTest
class BootstrapAdminLoginIntegrationTest {

  /** The bootstrap subject the test profile's {@code app.bootstrap.admin-subs} lists. */
  private static final String BOOTSTRAP_SUB = "11111111-2222-4333-8444-555555555555";

  private static final UUID SUBJECT = UUID.fromString(BOOTSTRAP_SUB);

  /** Concurrent first requests of one session-less client, each with a token of its own. */
  private static final int CONCURRENT_REQUESTS = 4;

  @Autowired private CustomJwtGrantedAuthoritiesConverter converter;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  /** Other active admins this class switched off, so the grant is due, and turns back on. */
  private final List<UUID> parkedAdmins = new ArrayList<>();

  /**
   * Parks every other active Admin holder the shared container holds, since the grant applies only
   * while no other active admin exists.
   */
  @BeforeEach
  void parkOtherActiveAdmins() {
    parkedAdmins.addAll(
        jdbcTemplate.queryForList(
            "SELECT u.id FROM app_user u JOIN user_roles ur ON ur.user_id = u.id"
                + " JOIN role r ON r.id = ur.role_id WHERE r.code = ? AND u.id <> ?"
                + " AND u.approval_status = 'ACTIVE' AND u.in_keycloak AND u.enabled_in_keycloak",
            UUID.class,
            Roles.ADMIN,
            SUBJECT));
    parkedAdmins.forEach(id ->
        jdbcTemplate.update("UPDATE app_user SET enabled_in_keycloak = FALSE WHERE id = ?", id));
  }

  /** Removes the bootstrap subject's rows and turns the parked admins back on. */
  @AfterEach
  void restore() {
    jdbcTemplate.update("DELETE FROM notification WHERE recipient_user_id = ?", SUBJECT);
    jdbcTemplate.update("DELETE FROM audit_event WHERE subject_id = ?", SUBJECT);
    jdbcTemplate.update("DELETE FROM user_approval_event WHERE user_id = ?", SUBJECT);
    jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", SUBJECT);
    jdbcTemplate.update("DELETE FROM personal_blueprint WHERE owner_user_id = ?", SUBJECT);
    jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", SUBJECT);
    parkedAdmins.forEach(id ->
        jdbcTemplate.update("UPDATE app_user SET enabled_in_keycloak = TRUE WHERE id = ?", id));
    parkedAdmins.clear();
  }

  /**
   * Builds an access token of the bootstrap subject in one session.
   *
   * @param session the {@code sid} claim, one per cache entry
   * @return a token the converter can be handed directly
   */
  private static Jwt tokenFor(String session) {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(BOOTSTRAP_SUB)
        .claim("sid", session)
        .claim("preferred_username", "bootstrap-admin")
        .claim("email", "bootstrap-admin@example.test")
        .build();
  }

  private List<String> authorities(Jwt jwt) {
    return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
  }

  /** Stores the bootstrap subject as an approved member holding no role yet. */
  private void storeBootstrapSubjectWithoutRoles() {
    User user = new User();
    user.setId(SUBJECT);
    user.setUsername("bootstrap-admin");
    user.setEmail("bootstrap-admin@example.test");
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setRoles(new HashSet<>());
    userRepository.saveAndFlush(user);
  }

  @Test
  @DisplayName("a brand-new bootstrap subject is authorised as Admin on its first request")
  void aNewBootstrapSubjectIsAdminOnItsFirstRequest() {
    assertThat(authorities(tokenFor("first-login")))
        .as("the granted role's permissions are read after the login transaction committed")
        .contains(Roles.authority(Roles.ADMIN));
  }

  @Test
  @DisplayName("a stored bootstrap subject is authorised as Admin on the request that earns it")
  void aStoredBootstrapSubjectIsAdminOnTheGrantingRequest() {
    storeBootstrapSubjectWithoutRoles();

    assertThat(authorities(tokenFor("granting-login"))).contains(Roles.authority(Roles.ADMIN));
  }

  @Test
  @DisplayName("concurrent first requests of a stored bootstrap subject all succeed")
  void concurrentFirstRequestsAllSucceed() throws Exception {
    storeBootstrapSubjectWithoutRoles();
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<List<String>>> requests = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      Jwt jwt = tokenFor("concurrent-" + i);
      requests.add(() -> {
        start.await();
        return authorities(jwt);
      });
    }
    List<Future<List<String>>> answers;
    try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {
      answers = requests.stream().map(pool::submit).toList();
      start.countDown();
      for (Future<List<String>> answer : answers) {
        assertThat(answer.get()).contains(Roles.authority(Roles.ADMIN));
      }
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_roles ur JOIN role r ON r.id = ur.role_id"
                    + " WHERE ur.user_id = ? AND r.code = ?",
                Long.class,
                SUBJECT,
                Roles.ADMIN))
        .isOne();
  }
}
