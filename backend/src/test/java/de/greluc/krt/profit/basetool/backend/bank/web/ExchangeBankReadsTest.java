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

import de.greluc.krt.profit.basetool.backend.bank.internal.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankLedgerService;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Account;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Actor;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Holder;
import de.greluc.krt.profit.basetool.backend.exchange.ExchangePublishedSchemas;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeMetricNames;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeNameWithholdings;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The bank reads on the exchange (REQ-BXCH-002, REQ-BXCH-007): the accounts the member views with
 * the two facts about the member, the redacted bookings and the daily balance series, their
 * allow-lists, paging and refusals, against a seeded {@link BankAccessWorld}.
 */
@SpringBootTest
@Transactional
class ExchangeBankReadsTest {

  private static final Set<String> ACCOUNT_FIELDS =
      Set.of(
          "accountId",
          "accountNo",
          "name",
          "type",
          "orgUnit",
          "balance",
          "balanceTarget",
          "delta30d",
          "sparkline",
          "requestable",
          "ownLimit");

  private static final Set<String> BOOKING_FIELDS =
      Set.of(
          "postingId",
          "transactionId",
          "type",
          "amount",
          "createdAt",
          "note",
          "justification",
          "reversedTransactionId",
          "counterAccount",
          "intraAccount",
          "transferFee",
          "counterpartyHandle",
          "counterpartyOrgUnitName");

  @Autowired private WebApplicationContext context;
  @Autowired private EntityManager entityManager;
  @Autowired private BankLedgerService ledgerService;
  @Autowired private ExchangeNameWithholdings nameWithholdings;
  @Autowired private MeterRegistry meterRegistry;

  private BankAccessWorld world;
  private BankExchangeRelay relay;

  @BeforeEach
  void setUp() {
    world = new BankAccessSeeder(context).seed();
    relay = new BankExchangeRelay(context);
  }

  @Test
  void listsTheViewableAccountsKrtFirstWithTheTwoFactsAboutTheMember() throws Exception {
    JsonNode page = ok(accounts(Actor.MEMBER, Map.of()));

    assertThat(seeded(page.path("items")))
        .containsExactly(Account.CARTEL, Account.OWN_ORG_UNIT, Account.SPECIAL_SHARED);
    assertThat(page.path("removed").isArray() && page.path("removed").isEmpty()).isTrue();
    assertThat(page.path("hasMore").asBoolean()).isFalse();
    assertThat(page.path("nextCursor").isNull()).isTrue();
    JsonNode own = item(page, Account.OWN_ORG_UNIT);
    assertThat(fields(own)).isEqualTo(new TreeSet<>(ACCOUNT_FIELDS));
    assertThat(own.path("type").asString()).isEqualTo("ORG_UNIT");
    assertThat(own.path("balance").isIntegralNumber()).isTrue();
    assertThat(own.path("balance").asLong()).isEqualTo(1_000_000L);
    assertThat(own.path("delta30d").asLong()).isEqualTo(1_000_000L);
    assertThat(own.path("sparkline")).hasSize(30);
    assertThat(own.path("sparkline").get(29).asLong()).isEqualTo(1_000_000L);
    assertThat(own.path("balanceTarget").isNull()).isTrue();
    assertThat(own.path("requestable").asBoolean()).isTrue();
    assertThat(own.path("ownLimit").asLong()).isEqualTo(1_000L);
    assertThat(fields(own.path("orgUnit")))
        .containsExactlyInAnyOrder("id", "shorthand", "name", "kind");
    assertThat(own.path("orgUnit").path("kind").asString()).isEqualTo("SQUADRON");
    JsonNode shared = item(page, Account.SPECIAL_SHARED);
    assertThat(shared.path("orgUnit").isNull()).isTrue();
    assertThat(shared.path("requestable").asBoolean()).isFalse();
    assertThat(shared.path("ownLimit").isNull()).isTrue();
  }

  @Test
  void pagesTheAccountsWithAnOpaqueCursor() throws Exception {
    List<Account> seen = new ArrayList<>();
    Map<String, String> parameters = Map.of("limit", "1");
    JsonNode page;
    do {
      page = ok(accounts(Actor.MEMBER, parameters));
      assertThat(page.path("items")).hasSize(1);
      seen.addAll(seeded(page.path("items")));
      parameters =
          page.path("hasMore").asBoolean()
              ? Map.of("limit", "1", "cursor", page.path("nextCursor").asString())
              : Map.of();
    } while (page.path("hasMore").asBoolean());

    assertThat(seen).containsExactly(Account.CARTEL, Account.OWN_ORG_UNIT, Account.SPECIAL_SHARED);
    assertThat(page.path("nextCursor").isNull()).isTrue();
  }

  @Test
  void aCursorTheServerDidNotIssueIsExpired() throws Exception {
    MockHttpServletResponse response = accounts(Actor.MEMBER, Map.of("cursor", "a1.nonsense"));

    assertThat(response.getStatus()).isEqualTo(410);
    assertThat(relay.body(response).path("code").asString()).isEqualTo("CURSOR_EXPIRED");
  }

  @Test
  void countsEveryReadServedPerClientAndRoute() throws Exception {
    double before = reads("accounts");

    ok(accounts(Actor.MEMBER, Map.of()));

    assertThat(reads("accounts")).isEqualTo(before + 1);
  }

  @Test
  void listsTheBookingsNewestFirstWithTheMemberRedaction() throws Exception {
    UUID own = world.account(Account.OWN_ORG_UNIT);
    deposit(own, new BigDecimal("250"), null);

    JsonNode page = ok(bookings(Actor.MEMBER, Account.OWN_ORG_UNIT, Map.of()));

    JsonNode items = page.path("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).path("amount").asLong()).isEqualTo(250L);
    assertThat(items.get(1).path("amount").asLong()).isEqualTo(1_000_000L);
    assertThat(fields(items.get(0))).isEqualTo(new TreeSet<>(BOOKING_FIELDS));
    assertThat(items.get(0).path("type").asString()).isEqualTo("DEPOSIT");
    assertThat(items.get(0).path("counterAccount").isNull()).isTrue();
    assertThat(items.get(0).path("transferFee").isNull()).isTrue();
    assertThat(page.path("removed").isEmpty()).isTrue();
  }

  @Test
  void pagesTheBookingsAndBoundsThemByTime() throws Exception {
    UUID own = world.account(Account.OWN_ORG_UNIT);
    deposit(own, new BigDecimal("250"), null);

    JsonNode first = ok(bookings(Actor.MEMBER, Account.OWN_ORG_UNIT, Map.of("limit", "1")));
    JsonNode second =
        ok(
            bookings(
                Actor.MEMBER,
                Account.OWN_ORG_UNIT,
                Map.of("limit", "1", "cursor", first.path("nextCursor").asString())));
    JsonNode none =
        ok(
            bookings(
                Actor.MEMBER,
                Account.OWN_ORG_UNIT,
                Map.of("to", Instant.now().minus(1, ChronoUnit.DAYS).toString())));

    assertThat(first.path("hasMore").asBoolean()).isTrue();
    assertThat(first.path("items").get(0).path("amount").asLong()).isEqualTo(250L);
    assertThat(second.path("hasMore").asBoolean()).isFalse();
    assertThat(second.path("items").get(0).path("amount").asLong()).isEqualTo(1_000_000L);
    assertThat(none.path("items").isEmpty()).isTrue();
  }

  @Test
  void aWithheldCounterpartyLosesTheHandleButNotTheBooking() throws Exception {
    UUID own = world.account(Account.OWN_ORG_UNIT);
    UUID counterparty = world.user(Actor.STAFFELLEITER);
    deposit(own, new BigDecimal("250"), counterparty);

    JsonNode shared = ok(bookings(Actor.MEMBER, Account.OWN_ORG_UNIT, Map.of()));
    nameWithholdings.update(counterparty, true, null);
    JsonNode withheld = ok(bookings(Actor.MEMBER, Account.OWN_ORG_UNIT, Map.of()));

    assertThat(shared.path("items").get(0).path("counterpartyHandle").asString())
        .contains(world.username(Actor.STAFFELLEITER));
    assertThat(withheld.path("items")).hasSize(2);
    assertThat(withheld.path("items").get(0).path("counterpartyHandle").isNull()).isTrue();
  }

  @Test
  void aHiddenAClosedAndAnUnknownAccountAnswerTheSameNotFound() throws Exception {
    double before = refused();

    List<MockHttpServletResponse> answers =
        List.of(
            bookings(Actor.MEMBER, Account.SK_ORG_UNIT, Map.of()),
            bookings(Actor.BEREICHSLEITER, Account.CLOSED, Map.of()),
            relay.read(
                world.user(Actor.MEMBER), "/accounts/" + UUID.randomUUID() + "/bookings", Map.of()),
            series(Actor.MEMBER, Account.SK_ORG_UNIT, last(30)));

    for (MockHttpServletResponse answer : answers) {
      assertThat(answer.getStatus()).isEqualTo(404);
      assertThat(relay.body(answer).path("code").asString()).isEqualTo("NOT_FOUND");
    }
    assertThat(refused()).isEqualTo(before + answers.size());
  }

  @Test
  void theBalanceSeriesHasOneDailyPointPerDayAndTheTarget() throws Exception {
    JsonNode series = ok(series(Actor.MEMBER, Account.OWN_ORG_UNIT, last(300)));

    assertThat(fields(series)).containsExactlyInAnyOrder("points", "balanceTarget");
    JsonNode points = series.path("points");
    assertThat(points).hasSize(301);
    assertThat(fields(points.get(0))).containsExactlyInAnyOrder("date", "balance");
    assertThat(points.get(0).path("date").asString()).matches("\\d{4}-\\d{2}-\\d{2}");
    assertThat(points.get(300).path("balance").asLong()).isEqualTo(1_000_000L);
  }

  @Test
  void theBalanceSeriesNeedsBothBoundsAndAtMostAYear() throws Exception {
    Instant now = Instant.now();
    List<Map<String, String>> invalid =
        List.of(
            Map.of("from", now.minus(10, ChronoUnit.DAYS).toString()),
            Map.of("to", now.toString()),
            Map.of("from", now.minus(366, ChronoUnit.DAYS).toString(), "to", now.toString()),
            Map.of("from", now.toString(), "to", now.minus(1, ChronoUnit.DAYS).toString()));

    for (Map<String, String> parameters : invalid) {
      MockHttpServletResponse answer = series(Actor.MEMBER, Account.OWN_ORG_UNIT, parameters);
      assertThat(answer.getStatus()).as(parameters.toString()).isEqualTo(400);
    }
  }

  @Test
  void everyAnswerMatchesItsPublishedSchema() throws Exception {
    deposit(
        world.account(Account.OWN_ORG_UNIT),
        new BigDecimal("250"),
        world.user(Actor.STAFFELLEITER));

    ExchangePublishedSchemas.assertMatches(
        "page.schema.json#/$defs/bankAccountPage",
        accounts(Actor.BEREICHSLEITER, Map.of("limit", "2")).getContentAsString(),
        "accounts");
    ExchangePublishedSchemas.assertMatches(
        "page.schema.json#/$defs/bankBookingPage",
        bookings(Actor.MEMBER, Account.OWN_ORG_UNIT, Map.of()).getContentAsString(),
        "bookings");
    ExchangePublishedSchemas.assertMatches(
        "bank-balance-series.schema.json",
        series(Actor.MEMBER, Account.OWN_ORG_UNIT, last(365)).getContentAsString(),
        "balance series");
  }

  @Test
  void anAdministratorActsOnlyThroughTheirBankRolesGrantsAndMemberships() throws Exception {
    JsonNode page = ok(accounts(Actor.ADMIN, Map.of()));

    assertThat(page.path("items").isEmpty()).isTrue();
    assertThat(bookings(Actor.ADMIN, Account.CARTEL, Map.of()).getStatus()).isEqualTo(404);
  }

  /**
   * Reads the accounts as an actor.
   *
   * @param actor the actor
   * @param parameters the query parameters
   * @return the response
   * @throws Exception when MockMvc fails
   */
  @NotNull
  private MockHttpServletResponse accounts(
      @NotNull Actor actor, @NotNull Map<String, String> parameters) throws Exception {
    return relay.read(world.user(actor), "/accounts", parameters);
  }

  /**
   * Reads the bookings of a seeded account as an actor.
   *
   * @param actor the actor
   * @param account the account
   * @param parameters the query parameters
   * @return the response
   * @throws Exception when MockMvc fails
   */
  @NotNull
  private MockHttpServletResponse bookings(
      @NotNull Actor actor, @NotNull Account account, @NotNull Map<String, String> parameters)
      throws Exception {
    return relay.read(
        world.user(actor), "/accounts/" + world.account(account) + "/bookings", parameters);
  }

  /**
   * Reads the balance series of a seeded account as an actor.
   *
   * @param actor the actor
   * @param account the account
   * @param parameters the query parameters
   * @return the response
   * @throws Exception when MockMvc fails
   */
  @NotNull
  private MockHttpServletResponse series(
      @NotNull Actor actor, @NotNull Account account, @NotNull Map<String, String> parameters)
      throws Exception {
    return relay.read(
        world.user(actor), "/accounts/" + world.account(account) + "/balance-series", parameters);
  }

  /**
   * The bounds of the last days up to now.
   *
   * @param days the span in days
   * @return {@code from} and {@code to}
   */
  @NotNull
  private static Map<String, String> last(int days) {
    Instant now = Instant.now();
    return Map.of("from", now.minus(days, ChronoUnit.DAYS).toString(), "to", now.toString());
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
   * The seeded accounts of a listing, in listing order; foreign accounts are skipped.
   *
   * @param items the listed accounts
   * @return the seeded accounts
   */
  @NotNull
  private List<Account> seeded(@NotNull JsonNode items) {
    List<Account> listed = new ArrayList<>();
    for (JsonNode item : items) {
      Account account = world.accountOf(UUID.fromString(item.path("accountId").asString()));
      if (account != null) {
        listed.add(account);
      }
    }
    return listed;
  }

  /**
   * One seeded account of a page.
   *
   * @param page the page
   * @param account the account
   * @return its item
   */
  @NotNull
  private JsonNode item(@NotNull JsonNode page, @NotNull Account account) {
    String id = world.account(account).toString();
    for (JsonNode item : page.path("items")) {
      if (id.equals(item.path("accountId").asString())) {
        return item;
      }
    }
    throw new AssertionError(account + " is not listed");
  }

  /**
   * The member names of an object.
   *
   * @param node the object
   * @return its member names, sorted
   */
  @NotNull
  private static Set<String> fields(@NotNull JsonNode node) {
    return new TreeSet<>(node.propertyNames());
  }

  /**
   * Books a deposit onto an account as the seeded administrator.
   *
   * @param accountId the account
   * @param amount the amount
   * @param counterparty the registered Einzahler, or {@code null}
   */
  private void deposit(
      @NotNull UUID accountId, @NotNull BigDecimal amount, @Nullable UUID counterparty) {
    entityManager.flush();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                Jwt.withTokenValue("reads-seed")
                    .header("alg", "none")
                    .subject(world.user(Actor.ADMIN).toString())
                    .build(),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    try {
      ledgerService.bookDeposit(
          new BankDepositRequest(
              accountId,
              world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER),
              amount,
              null,
              counterparty,
              null));
    } finally {
      SecurityContextHolder.clearContext();
    }
    entityManager.flush();
  }

  /**
   * The served reads of the relay's client on a route.
   *
   * @param route the route tag
   * @return the counter's count
   */
  private double reads(@NotNull String route) {
    Counter counter =
        meterRegistry
            .find(ExchangeMetricNames.EXCHANGE_BANK_READS)
            .tag(MetricNames.TAG_CLIENT_ID, relay.client())
            .tag(ExchangeMetricNames.TAG_ROUTE, route)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /**
   * The bank refusals for an unknown, hidden or closed account.
   *
   * @return the counter's count
   */
  private double refused() {
    Counter counter =
        meterRegistry
            .find(ExchangeMetricNames.EXCHANGE_BANK_REFUSED)
            .tag(MetricNames.TAG_REASON, "not_found")
            .counter();
    return counter == null ? 0 : counter.count();
  }
}
