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

import de.greluc.krt.profit.basetool.backend.bank.internal.BankBookingRequest;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Actor;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Request;
import de.greluc.krt.profit.basetool.backend.exchange.ExchangePublishedSchemas;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeMetricNames;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeNameWithholdings;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The read-only bank-staff queue on the exchange (REQ-BXCH-005): who sees which pending requests in
 * {@code open} and {@code toApprove}, the confirm and balance facts, the allow-list, paging and the
 * {@code 403 NOT_BANK_STAFF} refusal, against a seeded {@link BankAccessWorld}.
 */
@SpringBootTest
@Transactional
class ExchangeBankQueueTest {

  private static final Set<String> ITEM_FIELDS =
      Set.of(
          "id",
          "type",
          "amount",
          "createdAt",
          "ageHours",
          "account",
          "targetAccount",
          "requesterHandle",
          "justification",
          "note",
          "requiresOwnerApproval",
          "requiredApprover",
          "ownerApprovalGranted",
          "mayConfirm",
          "accountBalance",
          "client",
          "link");

  /** The requests on the own Staffel's and the KRT account, the employees' granted accounts. */
  private static final Set<Request> ON_GRANTED_ACCOUNTS =
      EnumSet.complementOf(
          EnumSet.of(Request.CONFIRM_WITHDRAWAL_SPECIAL, Request.APPROVE_SK, Request.APPROVE_AREA));

  @Autowired private WebApplicationContext context;
  @Autowired private EntityManager entityManager;
  @Autowired private BankBookingRequestRepository requestRepository;
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
  void anEmployeeSeesThePendingRequestsOnTheirGrantedAccounts() throws Exception {
    JsonNode queue = ok(queue(Actor.EMPLOYEE_WITHDRAW, Map.of()));

    Map<Request, JsonNode> open = seeded(queue.path("open"));
    assertThat(open.keySet()).isEqualTo(ON_GRANTED_ACCOUNTS);
    assertThat(seeded(queue.path("toApprove"))).isEmpty();
    open.forEach((request, item) ->
        assertThat(item.path("mayConfirm").asBoolean())
            .as(request.name())
            .isEqualTo("WITHDRAWAL".equals(item.path("type").asString())));
    assertThat(open.get(Request.CONFIRM_DEPOSIT_OWN).path("accountBalance").asLong())
        .isEqualTo(1_000_000L);
  }

  @Test
  void theBankleitungSeesTheWholeBankAndApprovesTheMiddleKrtBand() throws Exception {
    JsonNode queue = ok(queue(Actor.MANAGEMENT, Map.of()));

    Map<Request, JsonNode> open = seeded(queue.path("open"));
    assertThat(open.keySet()).isEqualTo(EnumSet.allOf(Request.class));
    assertThat(open.values()).allMatch(item -> item.path("mayConfirm").asBoolean());
    assertThat(open.get(Request.CONFIRM_WITHDRAWAL_SPECIAL).path("accountBalance").asLong())
        .isEqualTo(1_000_000L);
    assertThat(seeded(queue.path("toApprove")).keySet())
        .containsExactlyInAnyOrder(
            Request.APPROVE_CARTEL_MANAGEMENT, Request.CONFIRM_WITHDRAWAL_CARTEL_UNAPPROVED);
  }

  @Test
  void anApproverWithoutABankRoleSeesOnlyWhatWaitsForThem() throws Exception {
    JsonNode staffelleiter = ok(queue(Actor.STAFFELLEITER, Map.of()));
    JsonNode ol = ok(queue(Actor.OL_MEMBER, Map.of()));

    assertThat(seeded(staffelleiter.path("open"))).isEmpty();
    Map<Request, JsonNode> waiting = seeded(staffelleiter.path("toApprove"));
    assertThat(waiting.keySet()).containsExactly(Request.APPROVE_OWN);
    JsonNode item = waiting.get(Request.APPROVE_OWN);
    assertThat(item.path("mayConfirm").asBoolean()).isFalse();
    assertThat(item.path("accountBalance").isNull()).isTrue();
    assertThat(item.path("requiredApprover").asString()).isEqualTo("RESPONSIBLE_HOLDER");
    assertThat(seeded(ol.path("toApprove")).keySet()).containsExactly(Request.APPROVE_CARTEL_OL);
  }

  @Test
  void anOwnRequestIsListedButMayNotBeConfirmedAndNeverWaitsForTheRequester() throws Exception {
    BankBookingRequest own = request(Request.CONFIRM_WITHDRAWAL_OWN);
    own.setRequestedBy(world.user(Actor.EMPLOYEE_WITHDRAW));
    BankBookingRequest approval = request(Request.APPROVE_CARTEL_MANAGEMENT);
    approval.setRequestedBy(world.user(Actor.MANAGEMENT));
    entityManager.flush();

    JsonNode employee = ok(queue(Actor.EMPLOYEE_WITHDRAW, Map.of()));
    JsonNode management = ok(queue(Actor.MANAGEMENT, Map.of()));

    assertThat(
            seeded(employee.path("open"))
                .get(Request.CONFIRM_WITHDRAWAL_OWN)
                .path("mayConfirm")
                .asBoolean())
        .isFalse();
    assertThat(seeded(management.path("toApprove")).keySet())
        .containsExactly(Request.CONFIRM_WITHDRAWAL_CARTEL_UNAPPROVED);
  }

  @Test
  void aMemberWithoutBankRoleGrantOrApprovalIsRefusedAndCounted() throws Exception {
    double before = refused();

    for (Actor actor : List.of(Actor.MEMBER, Actor.ADMIN)) {
      MockHttpServletResponse answer = queue(actor, Map.of());
      assertThat(answer.getStatus()).as(actor.name()).isEqualTo(403);
      assertThat(relay.body(answer).path("code").asString()).isEqualTo("NOT_BANK_STAFF");
    }
    assertThat(refused()).isEqualTo(before + 2);
  }

  @Test
  void anEmployeeWithoutGrantsSeesAnEmptyQueue() throws Exception {
    JsonNode queue = ok(queue(Actor.EMPLOYEE, Map.of()));

    assertThat(seeded(queue.path("open"))).isEmpty();
    assertThat(seeded(queue.path("toApprove"))).isEmpty();
  }

  @Test
  void anItemCarriesExactlyTheAllowListedFieldsAndALinkIntoTheBasetool() throws Exception {
    JsonNode queue = ok(queue(Actor.MANAGEMENT, Map.of()));

    JsonNode transfer = seeded(queue.path("open")).get(Request.CONFIRM_TRANSFER_OWN);
    assertThat(new TreeSet<>(transfer.propertyNames())).isEqualTo(new TreeSet<>(ITEM_FIELDS));
    assertThat(transfer.path("link").asString())
        .isEqualTo(
            "https://profit-base.online/bank/requests/"
                + world.request(Request.CONFIRM_TRANSFER_OWN));
    assertThat(transfer.path("client").isNull()).isTrue();
    assertThat(transfer.path("requesterHandle").asString()).isEqualTo(world.username(Actor.MEMBER));
    assertThat(transfer.path("amount").asLong()).isEqualTo(500L);
    assertThat(transfer.path("ageHours").asLong()).isZero();
    assertThat(new TreeSet<>(transfer.path("account").propertyNames()))
        .containsExactly("id", "name", "no", "orgUnitShorthand");
    assertThat(new TreeSet<>(transfer.path("targetAccount").propertyNames()))
        .containsExactly("id", "no");
    assertThat(transfer.path("targetAccount").path("id").asString())
        .isEqualTo(world.account(BankAccessMatrix.Account.CARTEL).toString());
    assertThat(
            seeded(queue.path("open"))
                .get(Request.CONFIRM_DEPOSIT_OWN)
                .path("targetAccount")
                .isNull())
        .isTrue();
    ExchangePublishedSchemas.assertMatches(
        "bank-queue.schema.json", queue(Actor.MANAGEMENT, Map.of()).getContentAsString(), "queue");
  }

  @Test
  void aRequesterWhoWithholdsTheirNameLosesTheHandleButNotTheRequest() throws Exception {
    nameWithholdings.update(world.user(Actor.MEMBER), true, null);

    Map<Request, JsonNode> open = seeded(ok(queue(Actor.MANAGEMENT, Map.of())).path("open"));

    assertThat(open).hasSize(Request.values().length);
    assertThat(open.values()).allMatch(item -> item.path("requesterHandle").isNull());
  }

  @Test
  void theOldestRequestIsOverdueFromFortyEightHoursOn() throws Exception {
    JsonNode fresh = ok(queue(Actor.STAFFELLEITER, Map.of()));
    age(Request.APPROVE_OWN, 48);
    JsonNode overdue = ok(queue(Actor.STAFFELLEITER, Map.of()));

    assertThat(fresh.path("oldestOverdue").asBoolean()).isFalse();
    assertThat(overdue.path("oldestOverdue").asBoolean()).isTrue();
    assertThat(Instant.parse(overdue.path("oldestOpenAt").asString()))
        .isBefore(Instant.now().minus(47, ChronoUnit.HOURS));
    assertThat(seeded(overdue.path("toApprove")).get(Request.APPROVE_OWN).path("ageHours").asLong())
        .isEqualTo(48L);
  }

  @Test
  void pagesBothSectionsOldestFirstWithOneCursor() throws Exception {
    List<UUID> open = new ArrayList<>();
    List<UUID> toApprove = new ArrayList<>();
    Map<String, String> parameters = Map.of("limit", "1");
    JsonNode page;
    do {
      page = ok(queue(Actor.MANAGEMENT, parameters));
      assertThat(page.path("open").size()).isLessThanOrEqualTo(1);
      assertThat(page.path("toApprove").size()).isLessThanOrEqualTo(1);
      page.path("open").forEach(item -> open.add(UUID.fromString(item.path("id").asString())));
      page.path("toApprove")
          .forEach(item -> toApprove.add(UUID.fromString(item.path("id").asString())));
      parameters =
          page.path("hasMore").asBoolean()
              ? Map.of("limit", "1", "cursor", page.path("nextCursor").asString())
              : Map.of();
    } while (page.path("hasMore").asBoolean());

    JsonNode whole = ok(queue(Actor.MANAGEMENT, Map.of()));
    assertThat(open).isEqualTo(ids(whole.path("open")));
    assertThat(toApprove).isEqualTo(ids(whole.path("toApprove")));
    assertThat(page.path("nextCursor").isNull()).isTrue();
  }

  @Test
  void aCursorTheServerDidNotIssueIsExpired() throws Exception {
    MockHttpServletResponse response = queue(Actor.MANAGEMENT, Map.of("cursor", "q1.s.nonsense"));

    assertThat(response.getStatus()).isEqualTo(410);
    assertThat(relay.body(response).path("code").asString()).isEqualTo("CURSOR_EXPIRED");
  }

  @Test
  void countsEverySnapshotServedPerClient() throws Exception {
    double before = reads();

    ok(queue(Actor.MANAGEMENT, Map.of()));

    assertThat(reads()).isEqualTo(before + 1);
  }

  /**
   * Reads the queue as an actor.
   *
   * @param actor the actor
   * @param parameters the query parameters
   * @return the response
   * @throws Exception when MockMvc fails
   */
  @NotNull
  private MockHttpServletResponse queue(
      @NotNull Actor actor, @NotNull Map<String, String> parameters) throws Exception {
    return relay.read(world.user(actor), "/queue", parameters);
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
   * The seeded requests of a section by name; foreign requests are skipped.
   *
   * @param items the section's items
   * @return the seeded requests' items
   */
  @NotNull
  private Map<Request, JsonNode> seeded(@NotNull JsonNode items) {
    Map<Request, JsonNode> listed = new HashMap<>();
    for (JsonNode item : items) {
      Request request = world.requestOf(UUID.fromString(item.path("id").asString()));
      if (request != null) {
        listed.put(request, item);
      }
    }
    return listed;
  }

  /**
   * The request ids of a section in answer order.
   *
   * @param items the section's items
   * @return their ids
   */
  @NotNull
  private static List<UUID> ids(@NotNull JsonNode items) {
    List<UUID> ids = new ArrayList<>();
    items.forEach(item -> ids.add(UUID.fromString(item.path("id").asString())));
    return ids;
  }

  /**
   * Loads a seeded request.
   *
   * @param request the request
   * @return its entity
   */
  @NotNull
  private BankBookingRequest request(@NotNull Request request) {
    return requestRepository.findById(world.request(request)).orElseThrow();
  }

  /**
   * Moves a seeded request's creation back by whole hours.
   *
   * @param request the request
   * @param hours the age to give it
   */
  private void age(@NotNull Request request, int hours) {
    entityManager.flush();
    entityManager
        .createNativeQuery("UPDATE bank_booking_request SET created_at = :at WHERE id = :id")
        .setParameter("at", Timestamp.from(Instant.now().minus(hours, ChronoUnit.HOURS)))
        .setParameter("id", world.request(request))
        .executeUpdate();
    entityManager.clear();
  }

  /**
   * The served snapshots of the relay's client.
   *
   * @return the counter's count
   */
  private double reads() {
    Counter counter =
        meterRegistry
            .find(ExchangeMetricNames.EXCHANGE_BANK_READS)
            .tag(MetricNames.TAG_CLIENT_ID, relay.client())
            .tag(ExchangeMetricNames.TAG_ROUTE, "queue")
            .counter();
    return counter == null ? 0 : counter.count();
  }

  /**
   * The bank refusals of a member who is no bank staff.
   *
   * @return the counter's count
   */
  private double refused() {
    Counter counter =
        meterRegistry
            .find(ExchangeMetricNames.EXCHANGE_BANK_REFUSED)
            .tag(MetricNames.TAG_REASON, "not_bank_staff")
            .counter();
    return counter == null ? 0 : counter.count();
  }
}
