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

package de.greluc.krt.profit.basetool.backend.bank.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.exchange.ExchangePublishedSchemas;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The change feed of the member's own booking requests on committed transactions (REQ-BXCH-003,
 * REQ-XCH-013): a request raised, decided or removed after the snapshot follows in the feed, the
 * web-raised ones too; another member's request never does, and a cursor past the horizon has
 * expired.
 */
@SpringBootTest
class ExchangeBankRequestFeedTest {

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private ExchangeSettingsRepository settingsRepository;

  private BankExchangeRelay relay;
  private UUID member;
  private UUID other;
  private UUID account;
  private boolean wasEnabled;
  private boolean wasBankEnabled;

  @BeforeEach
  void setUp() {
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    wasEnabled = settings.isEnabled();
    wasBankEnabled = settings.isBankEnabled();
    relay = new BankExchangeRelay(context);
    member = user("bank-feed-member");
    other = user("bank-feed-other");
    account = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_account (id, account_no, name, type) VALUES (?, ?, 'Feed', 'SPECIAL')",
        account,
        "KB-F" + account.toString().substring(0, 8));
  }

  @AfterEach
  void tearDown() {
    jdbc.update(
        "UPDATE exchange_feed_horizon SET purged_through_tx = 0, purged_through_seq = 0 WHERE id ="
            + " 1");
    jdbc.update("DELETE FROM bank_booking_request WHERE account_id = ?", account);
    jdbc.update("DELETE FROM bank_account WHERE id = ?", account);
    jdbc.update("DELETE FROM exchange_client WHERE client_id = ?", relay.client());
    for (UUID id : List.of(member, other)) {
      jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
      jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
    ExchangeSettings settings =
        settingsRepository.findById(ExchangeSettings.SINGLETON_ID).orElseThrow();
    settings.setEnabled(wasEnabled);
    settings.setBankEnabled(wasBankEnabled);
    settingsRepository.saveAndFlush(settings);
  }

  @Test
  void aRequestRaisedDecidedOrRemovedAfterTheSnapshotFollowsInTheFeed() throws Exception {
    UUID decided = request(member);
    UUID removed = request(member);
    String cursor = snapshotEnd();

    UUID raised = request(member);
    jdbc.update(
        "UPDATE bank_booking_request SET status = 'CANCELLED', decided_at = now() WHERE id = ?",
        decided);
    jdbc.update("DELETE FROM bank_booking_request WHERE id = ?", removed);

    JsonNode page = ok(relay.read(member, "/requests", Map.of("cursor", cursor)));

    List<String> ids = new ArrayList<>();
    page.path("items").forEach(item -> ids.add(item.path("id").asString()));
    assertThat(ids).containsExactlyInAnyOrder(raised.toString(), decided.toString());
    for (JsonNode item : page.path("items")) {
      if (decided.toString().equals(item.path("id").asString())) {
        assertThat(item.path("status").asString()).isEqualTo("CANCELLED");
      }
    }
    assertThat(page.path("removed")).hasSize(1);
    assertThat(page.path("removed").get(0).path("key").asString()).isEqualTo(removed.toString());
    assertThat(page.path("hasMore").asBoolean()).isFalse();
    assertThat(page.path("nextCursor").asString()).startsWith("f1.");
    ExchangePublishedSchemas.assertMatches(
        "page.schema.json#/$defs/bankRequestPage", page.toString(), "the request feed");
  }

  @Test
  void anotherMembersRequestNeverReachesTheFeed() throws Exception {
    String cursor = snapshotEnd();

    request(other);

    JsonNode page = ok(relay.read(member, "/requests", Map.of("cursor", cursor)));
    assertThat(page.path("items").isEmpty()).isTrue();
    assertThat(page.path("removed").isEmpty()).isTrue();
  }

  @Test
  void aCursorPastTheHorizonHasExpired() throws Exception {
    jdbc.update(
        "UPDATE exchange_feed_horizon SET purged_through_tx = 9000000000, purged_through_seq = 1"
            + " WHERE id = 1");

    MockHttpServletResponse response = relay.read(member, "/requests", Map.of("cursor", "f1.5.0"));

    assertThat(response.getStatus()).isEqualTo(410);
    assertThat(relay.body(response).path("code").asString()).isEqualTo("CURSOR_EXPIRED");
  }

  /**
   * Reads the member's snapshot to its end.
   *
   * @return the feed cursor the last snapshot page hands over
   * @throws Exception when MockMvc fails
   */
  @NotNull
  private String snapshotEnd() throws Exception {
    JsonNode page = ok(relay.read(member, "/requests", Map.of()));
    assertThat(page.path("hasMore").asBoolean()).isFalse();
    return page.path("nextCursor").asString();
  }

  /**
   * Raises a pending deposit request on the test account as the web's request path writes it.
   *
   * @param requester the requesting member
   * @return its id
   */
  @NotNull
  private UUID request(@NotNull UUID requester) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO bank_booking_request (id, account_id, type, amount, requested_by,
                                          requester_handle)
        VALUES (?, ?, 'DEPOSIT', 100, ?, 'bank-feed')
        """,
        id,
        account,
        requester);
    return id;
  }

  /**
   * Asserts a success and parses it.
   *
   * @param response the response
   * @return the body
   * @throws Exception when the body is no JSON
   */
  @NotNull
  private JsonNode ok(@NotNull MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    return relay.body(response);
  }

  /**
   * Seeds a committed member.
   *
   * @param username the username prefix
   * @return the member's id
   */
  @NotNull
  private UUID user(@NotNull String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username + "-" + UUID.randomUUID().toString().substring(0, 8));
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setRoles(new HashSet<>(Set.of(roleRepository.findByCode(Roles.KRT_MEMBER).orElseThrow())));
    return userRepository.saveAndFlush(user).getId();
  }
}
