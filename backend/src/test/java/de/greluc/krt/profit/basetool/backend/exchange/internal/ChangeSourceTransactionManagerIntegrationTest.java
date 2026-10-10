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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.platform.api.SubjectAuthentication;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The transaction manager attributes each writing transaction from the current authentication, and
 * leaves read-only transactions alone (ADR-0224).
 */
@SpringBootTest
class ChangeSourceTransactionManagerIntegrationTest {

  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;
  @Autowired private ExchangeChangeRepository changeRepository;

  private UUID member;

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("tx-source-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    member = userRepository.saveAndFlush(user).getId();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
    jdbc.update("DELETE FROM app_user WHERE id = ?", member);
  }

  @Test
  void theManagerIsTheAttributingOne() {
    assertThat(transactionManager).isInstanceOf(ChangeSourceTransactionManager.class);
  }

  @Test
  void aBrowserSessionWritesAsWeb() {
    authenticate(jwt("basetool-frontend"));

    write("from-web");

    assertThat(last().getSourceChannel()).isEqualTo("web");
  }

  @Test
  void theAppWritesAsApp() {
    authenticate(jwt("basetool-android"));

    write("from-app");

    assertThat(last().getSourceChannel()).isEqualTo("app");
  }

  @Test
  void aRelayedClientWritesAsThatClientAndInstallation() {
    authenticate(new Relayed("versekit", "k".repeat(43)));

    write("from-client");

    ExchangeChange change = last();
    assertThat(change.getSourceChannel()).isEqualTo("client");
    assertThat(change.getSourceClient()).isEqualTo("versekit");
    assertThat(change.getSourceKey()).isEqualTo("k".repeat(43));
  }

  @Test
  void anUnknownClientAndNoAuthenticationWriteAsSystem() {
    authenticate(jwt("some-other-client"));
    write("from-other");
    assertThat(last().getSourceChannel()).isEqualTo("system");

    SecurityContextHolder.clearContext();
    write("from-nobody");
    assertThat(last().getSourceChannel()).isEqualTo("system");
  }

  @Test
  void theVariableEndsWithItsTransaction() {
    authenticate(jwt("basetool-frontend"));
    write("first");

    TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
    readOnly.setReadOnly(true);
    String inReadOnly =
        readOnly.execute(
            s ->
                jdbc.queryForObject(
                    "SELECT current_setting('basetool.change_source', true)", String.class));

    assertThat(inReadOnly).isNullOrEmpty();
  }

  /**
   * Inserts one blueprint in a writing transaction.
   *
   * @param productKey the product key
   */
  private void write(@NotNull String productKey) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            s ->
                jdbc.update(
                    """
                    INSERT INTO personal_blueprint (id, owner_user_id, product_key, product_name)
                    VALUES (?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    member,
                    productKey,
                    productKey));
  }

  /**
   * Returns the member's newest feed entry.
   *
   * @return the entry
   */
  private @NotNull ExchangeChange last() {
    List<ExchangeChange> changes = changeRepository.findAllByUserIdOrderBySeqAsc(member);
    return changes.getLast();
  }

  /**
   * Makes an authentication current.
   *
   * @param authentication the authentication
   */
  private static void authenticate(@NotNull Authentication authentication) {
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  /**
   * Builds a bearer authentication issued to a client.
   *
   * @param azp the client
   * @return the authentication
   */
  private @NotNull JwtAuthenticationToken jwt(@NotNull String azp) {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject(member.toString())
            .claim("azp", azp)
            .issuedAt(Instant.now())
            .build();
    return new JwtAuthenticationToken(jwt);
  }

  /** A relayed exchange request's authentication. */
  private final class Relayed extends AbstractAuthenticationToken implements SubjectAuthentication {

    private final String client;
    private final String key;

    /**
     * Creates it.
     *
     * @param client the relayed client
     * @param key the installation key
     */
    Relayed(@NotNull String client, @NotNull String key) {
      super(List.of());
      this.client = client;
      this.key = key;
      setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
      return "";
    }

    @Override
    public Object getPrincipal() {
      return member.toString();
    }

    @Override
    public @NotNull String subject() {
      return member.toString();
    }

    @Override
    public String externalClient() {
      return client;
    }

    @Override
    public String exchangeInstallationKey() {
      return key;
    }
  }
}
