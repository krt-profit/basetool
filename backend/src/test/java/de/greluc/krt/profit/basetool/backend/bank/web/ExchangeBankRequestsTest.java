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

import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Account;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Actor;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Request;
import de.greluc.krt.profit.basetool.backend.exchange.ExchangePublishedSchemas;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeMetricNames;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The member's own booking requests on the exchange as a snapshot (REQ-BXCH-003): every own
 * request, the web-raised ones too, with exactly the allow-listed fields, paged by an opaque cursor
 * that ends at the change feed, against a seeded {@link BankAccessWorld}. The feed itself runs on
 * committed transactions in {@code ExchangeBankRequestFeedTest}.
 */
@SpringBootTest
@Transactional
class ExchangeBankRequestsTest {

  private static final Set<String> REQUEST_FIELDS =
      Set.of(
          "id",
          "version",
          "status",
          "createdAt",
          "decidedAt",
          "type",
          "amount",
          "note",
          "justification",
          "account",
          "targetAccount",
          "requiresOwnerApproval",
          "requiredApprover",
          "ownerApprovalGranted",
          "rejectReason",
          "client",
          "splitEnabled",
          "splitPercent");

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager entityManager;
  @Autowired private MeterRegistry meterRegistry;

  private BankAccessWorld world;
  private BankExchangeRelay relay;

  @BeforeEach
  void setUp() {
    world = new BankAccessSeeder(context).seed();
    relay = new BankExchangeRelay(context);
  }

  @Test
  void listsEveryOwnRequestWithExactlyTheAllowListedFields() throws Exception {
    JsonNode page = ok(requests(Actor.MEMBER, Map.of()));

    assertThat(seeded(page.path("items"))).containsExactlyInAnyOrder(Request.values());
    assertThat(page.path("removed").isEmpty()).isTrue();
    assertThat(page.path("hasMore").asBoolean()).isFalse();
    assertThat(page.path("nextCursor").asString()).startsWith("f1.");
    for (JsonNode item : page.path("items")) {
      assertThat(new TreeSet<>(item.propertyNames())).isEqualTo(new TreeSet<>(REQUEST_FIELDS));
    }
    ExchangePublishedSchemas.assertMatches(
        "page.schema.json#/$defs/bankRequestPage", page.toString(), "own requests");
  }

  @Test
  void shapesATransferWithItsAccountsAndLeavesTheOriginNull() throws Exception {
    JsonNode transfer = item(ok(requests(Actor.MEMBER, Map.of())), Request.CONFIRM_TRANSFER_OWN);

    assertThat(transfer.path("type").asString()).isEqualTo("TRANSFER");
    assertThat(transfer.path("status").asString()).isEqualTo("PENDING");
    assertThat(transfer.path("amount").isIntegralNumber()).isTrue();
    assertThat(transfer.path("decidedAt").isNull()).isTrue();
    assertThat(transfer.path("client").isNull()).isTrue();
    assertThat(transfer.path("splitEnabled").asBoolean()).isFalse();
    assertThat(transfer.path("splitPercent").isNull()).isTrue();
    JsonNode account = transfer.path("account");
    assertThat(new TreeSet<>(account.propertyNames()))
        .containsExactly("id", "name", "no", "orgUnitShorthand");
    assertThat(account.path("id").asString())
        .isEqualTo(world.account(Account.OWN_ORG_UNIT).toString());
    assertThat(account.path("no").asString()).startsWith("KB-");
    assertThat(account.path("orgUnitShorthand").isString()).isTrue();
    assertThat(new TreeSet<>(transfer.path("targetAccount").propertyNames()))
        .containsExactly("id", "no");
    assertThat(transfer.path("targetAccount").path("id").asString())
        .isEqualTo(world.account(Account.CARTEL).toString());
  }

  @Test
  void carriesTheSplitOfADepositAndTheDecisionOfARejectedRequest() throws Exception {
    UUID deposit = world.request(Request.CONFIRM_DEPOSIT_OWN);
    UUID rejected = world.request(Request.REJECT_OWN);
    entityManager.flush();
    jdbc.update(
        "UPDATE bank_booking_request SET split_enabled = TRUE, split_percent = 25 WHERE id = ?",
        deposit);
    jdbc.update(
        """
        UPDATE bank_booking_request
           SET status = 'REJECTED', reject_reason = 'Kein Beleg', decided_at = now(),
               decider_handle = 'staff', staff_note = 'intern'
         WHERE id = ?
        """,
        rejected);
    entityManager.clear();

    JsonNode page = ok(requests(Actor.MEMBER, Map.of()));

    JsonNode split = item(page, Request.CONFIRM_DEPOSIT_OWN);
    assertThat(split.path("splitEnabled").asBoolean()).isTrue();
    assertThat(split.path("splitPercent").asInt()).isEqualTo(25);
    assertThat(split.path("targetAccount").isNull()).isTrue();
    JsonNode decided = item(page, Request.REJECT_OWN);
    assertThat(decided.path("status").asString()).isEqualTo("REJECTED");
    assertThat(decided.path("rejectReason").asString()).isEqualTo("Kein Beleg");
    assertThat(decided.path("decidedAt").isString()).isTrue();
    assertThat(decided.toString()).doesNotContain("intern", "staff");
    ExchangePublishedSchemas.assertMatches(
        "page.schema.json#/$defs/bankRequestPage", page.toString(), "decided requests");
  }

  @Test
  void anotherMemberSeesNoneOfTheMembersRequests() throws Exception {
    JsonNode page = ok(requests(Actor.STAFFELLEITER, Map.of()));

    assertThat(seeded(page.path("items"))).isEmpty();
  }

  @Test
  void pagesTheSnapshotWithAnOpaqueCursorThatEndsAtTheFeed() throws Exception {
    List<Request> seen = new ArrayList<>();
    Map<String, String> parameters = Map.of("limit", "4");
    JsonNode page;
    do {
      page = ok(requests(Actor.MEMBER, parameters));
      assertThat(page.path("items").size()).isLessThanOrEqualTo(4);
      seen.addAll(seeded(page.path("items")));
      parameters = Map.of("limit", "4", "cursor", page.path("nextCursor").asString());
    } while (page.path("hasMore").asBoolean());

    assertThat(seen).containsExactlyInAnyOrder(Request.values());
    assertThat(page.path("nextCursor").asString()).startsWith("f1.");
  }

  @Test
  void aCursorTheServerDidNotIssueIsExpired() throws Exception {
    MockHttpServletResponse response = requests(Actor.MEMBER, Map.of("cursor", "a1.nonsense"));

    assertThat(response.getStatus()).isEqualTo(410);
    assertThat(relay.body(response).path("code").asString()).isEqualTo("CURSOR_EXPIRED");
  }

  @Test
  void countsEveryReadServedPerClientAndRoute() throws Exception {
    double before = reads();

    ok(requests(Actor.MEMBER, Map.of()));

    assertThat(reads()).isEqualTo(before + 1);
  }

  /**
   * Reads the own requests as an actor.
   *
   * @param actor the actor
   * @param parameters the query parameters
   * @return the response
   * @throws Exception when MockMvc fails
   */
  @NotNull
  private MockHttpServletResponse requests(
      @NotNull Actor actor, @NotNull Map<String, String> parameters) throws Exception {
    return relay.read(world.user(actor), "/requests", parameters);
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
   * The seeded requests of a page, in page order; requests of the seeder's other rows are skipped.
   *
   * @param items the listed requests
   * @return the seeded requests
   */
  @NotNull
  private List<Request> seeded(@NotNull JsonNode items) {
    List<Request> listed = new ArrayList<>();
    for (JsonNode item : items) {
      Request request = world.requestOf(UUID.fromString(item.path("id").asString()));
      if (request != null) {
        listed.add(request);
      }
    }
    return listed;
  }

  /**
   * One seeded request of a page.
   *
   * @param page the page
   * @param request the request
   * @return its item
   */
  @NotNull
  private JsonNode item(@NotNull JsonNode page, @NotNull Request request) {
    String id = world.request(request).toString();
    for (JsonNode item : page.path("items")) {
      if (id.equals(item.path("id").asString())) {
        return item;
      }
    }
    throw new AssertionError(request + " is not listed");
  }

  /**
   * The served reads of the relay's client on the requests route.
   *
   * @return the counter's count
   */
  private double reads() {
    Counter counter =
        meterRegistry
            .find(ExchangeMetricNames.EXCHANGE_BANK_READS)
            .tag(MetricNames.TAG_CLIENT_ID, relay.client())
            .tag(ExchangeMetricNames.TAG_ROUTE, "requests")
            .counter();
    return counter == null ? 0 : counter.count();
  }
}
