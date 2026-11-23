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

package de.greluc.krt.profit.basetool.backend.exchange.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.greluc.krt.profit.basetool.backend.exchange.ExchangePublishedSchemas;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeMetricNames;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The cancel of an aUEC order under {@code exchange.joborders.write} and the rules every client
 * write on an existing order shares, against PostgreSQL through the relay: the order of the
 * refusals, the one refusal of every money fact decided under the order locks, the client's own
 * audit type, the web's finished notice naming the application, and the refusal metrics
 * (REQ-OXCH-005, REQ-OXCH-006, REQ-OBS-025).
 */
@SpringBootTest
class ExchangeJobOrderCancelTest {

  private static final String WRITTEN_SCHEMA = "job-order-write.schema.json#/$defs/written";

  /** The amount of the money operation {@link #submitMoney} records. */
  private static final long MONEY = 9_876_543L;

  /** The accountless holder name the money operation records. */
  private static final String HOLDER_NAME = "Cancel holder";

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JobOrderRepository jobOrders;

  private ExchangeJobOrderWorld world;
  private UUID logistician;

  @BeforeEach
  void setUp() {
    world = new ExchangeJobOrderWorld(context);
    logistician = world.getLogistician();
  }

  @AfterEach
  void tearDown() {
    world.close();
  }

  @Test
  void aLogisticianOfTheRequestingUnitCancelsAnActiveOrder() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);

    MockHttpServletResponse response = cancel(logistician, order, 0L);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    String content = response.getContentAsString();
    ExchangePublishedSchemas.assertMatches(WRITTEN_SCHEMA, content, "a cancelled order");
    JsonNode written = objectMapper.readTree(content);
    assertThat(written.propertyNames()).containsExactlyInAnyOrder("id", "status", "versions");
    assertThat(written.get("id").asString()).isEqualTo(order.toString());
    assertThat(written.get("status").asString()).isEqualTo("CANCELLED");
    assertThat(written.get("versions").get("lifecycle").asLong()).isEqualTo(1L);
    assertThat(status(order)).isEqualTo("CANCELLED");
  }

  @Test
  void anInProgressOrderIsCancelledToo() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    jdbc.update(
        "UPDATE job_order SET status = 'IN_PROGRESS', lifecycle_version = 1 WHERE id = ?", order);

    assertThat(cancel(logistician, order, 1L).getStatus()).isEqualTo(200);
    assertThat(status(order)).isEqualTo("CANCELLED");
  }

  @Test
  void anUnknownAHiddenAndADeletedOrderAnswerTheSameNotFound() throws Exception {
    UUID hidden = world.createAuec(logistician, 250_000L);
    UUID stranger = world.user("cancel-stranger", false);
    world.seat(stranger, world.squadron("Cancel elsewhere", null), "MEMBER", true);
    UUID deleted = world.createAuec(logistician, 1L);
    jdbc.update("DELETE FROM audit_event WHERE subject_id = ?", deleted);
    jdbc.update("DELETE FROM job_order WHERE id = ?", deleted);

    JsonNode unknown = assertRefused(cancel(stranger, UUID.randomUUID(), 0L), 404, "NOT_FOUND");
    JsonNode outside = assertRefused(cancel(stranger, hidden, 0L), 404, "NOT_FOUND");
    JsonNode gone = assertRefused(cancel(logistician, deleted, 0L), 404, "NOT_FOUND");

    assertThat(outside.get("title")).isEqualTo(unknown.get("title"));
    assertThat(gone.get("title")).isEqualTo(unknown.get("title"));
    assertThat(status(hidden)).isEqualTo("OPEN");
  }

  @Test
  void aMemberWhoReadsButDoesNotWriteTheOrderIsNotPermitted() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    UUID plain = world.user("cancel-plain", false);
    world.seat(plain, world.getRequesting(), "MEMBER", false);

    assertRefused(cancel(plain, order, 0L), 403, "ACCESS_DENIED");
    assertThat(status(order)).isEqualTo("OPEN");
  }

  @Test
  void aWriterOfTheResponsibleSideIsNotPermitted() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    UUID profitLogistician = world.user("cancel-profit-logistician", false);
    world.seat(profitLogistician, world.getResponsible(), "MEMBER", true);

    assertRefused(cancel(profitLogistician, order, 0L), 403, "ACCESS_DENIED");
    assertThat(status(order)).isEqualTo("OPEN");
  }

  @Test
  void materialAndItemOrdersAreNotPermitted() throws Exception {
    UUID material = world.stockOrder("MATERIAL");
    UUID item = world.stockOrder("ITEM");

    assertRefused(cancel(logistician, material, 0L), 403, "ACCESS_DENIED");
    assertRefused(cancel(logistician, item, 0L), 403, "ACCESS_DENIED");
    assertThat(status(material)).isEqualTo("OPEN");
    assertThat(status(item)).isEqualTo("OPEN");
  }

  @Test
  void aStaleLifecycleVersionIsAVersionConflict() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);

    assertRefused(cancel(logistician, order, 3L), 409, "OPTIMISTIC_LOCK");
    assertThat(status(order)).isEqualTo("OPEN");
  }

  @Test
  void aRetryAfterTheGatewaysCacheAnswersAVersionConflict() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    assertThat(cancel(logistician, order, 0L).getStatus()).isEqualTo(200);

    assertRefused(cancel(logistician, order, 0L), 409, "OPTIMISTIC_LOCK");
  }

  @Test
  void anInvalidResponsibilityAnswersItsOwnConflict() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    leaveTheProfitHierarchy();

    assertRefused(cancel(logistician, order, 0L), 409, "JOB_ORDER_RESPONSIBILITY_INVALID");
    assertThat(status(order)).isEqualTo("OPEN");
  }

  @Test
  void aCompletedOrRejectedOrderIsClosed() throws Exception {
    UUID completed = world.createAuec(logistician, 250_000L);
    UUID rejected = world.createAuec(logistician, 250_000L);
    close(completed, "COMPLETED");
    close(rejected, "REJECTED");

    assertRefused(cancel(logistician, completed, 1L), 409, "JOB_ORDER_CLOSED");
    assertRefused(cancel(logistician, rejected, 1L), 409, "JOB_ORDER_CLOSED");
    assertThat(status(completed)).isEqualTo("COMPLETED");
  }

  @Test
  void anOrderWhoseMoneyStartedAnswersOneRefusalNamingNoMoneyFact() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    submitMoney(order);

    MockHttpServletResponse response = cancel(logistician, order, 0L);

    JsonNode problem = assertRefused(response, 409, "JOB_ORDER_MONEY_STARTED");
    assertThat(response.getContentAsString()).doesNotContain(HOLDER_NAME);
    assertThat(problem.get("title").asString() + problem.get("detail").asString())
        .doesNotContain(String.valueOf(MONEY));
    assertThat(status(order)).isEqualTo("OPEN");
  }

  @Test
  void theRefusalsComeInTheSharedOrder() throws Exception {
    UUID plain = world.user("cancel-order-plain", false);
    world.seat(plain, world.getRequesting(), "MEMBER", false);
    UUID stranger = world.user("cancel-order-stranger", false);
    UUID material = world.stockOrder("MATERIAL");
    UUID closedWithMoney = world.createAuec(logistician, 250_000L);
    close(closedWithMoney, "REJECTED");
    submitMoney(closedWithMoney);
    leaveTheProfitHierarchy();

    assertRefused(cancel(stranger, material, 7L), 404, "NOT_FOUND");
    assertRefused(cancel(plain, closedWithMoney, 7L), 403, "ACCESS_DENIED");
    assertRefused(cancel(logistician, closedWithMoney, 7L), 409, "OPTIMISTIC_LOCK");
    assertRefused(
        cancel(logistician, closedWithMoney, 1L), 409, "JOB_ORDER_RESPONSIBILITY_INVALID");
  }

  @Test
  void aClosedOrderIsRefusedBeforeItsMoneyIsAskedAbout() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    close(order, "REJECTED");
    submitMoney(order);

    assertRefused(cancel(logistician, order, 1L), 409, "JOB_ORDER_CLOSED");
  }

  @Test
  void aFirstMoneySubmissionHoldingTheOrderLockWinsTheRace() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    CountDownLatch submitted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    MockHttpServletResponse response;
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<?> submission =
          pool.submit(() ->
              transactions.executeWithoutResult(_ -> {
                jobOrders.lockForScopedEdit(order);
                submitMoney(order);
                submitted.countDown();
                awaitQuietly(release);
              }));
      assertThat(submitted.await(30, TimeUnit.SECONDS)).isTrue();
      Future<MockHttpServletResponse> cancel = pool.submit(() -> cancel(logistician, order, 0L));
      await().pollDelay(Duration.ofMillis(500)).until(() -> !cancel.isDone());
      release.countDown();
      submission.get(30, TimeUnit.SECONDS);
      response = cancel.get(30, TimeUnit.SECONDS);
    }

    assertRefused(response, 409, "JOB_ORDER_MONEY_STARTED");
    assertThat(status(order)).isEqualTo("OPEN");
  }

  @Test
  void aClientsCancelRecordsItsOwnAuditTypeWithTheClientAndARefusalNothing() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    UUID refusedOrder = world.createAuec(logistician, 250_000L);
    assertRefused(cancel(logistician, refusedOrder, 5L), 409, "OPTIMISTIC_LOCK");

    assertThat(cancel(logistician, order, 0L).getStatus()).isEqualTo(200);

    List<Map<String, Object>> audits =
        jdbc.queryForList(
            "SELECT event_type, client_id, actor_user_id, details FROM audit_event"
                + " WHERE subject_id = ? AND event_type <> 'JOB_ORDER_CREATED_VIA_CLIENT'",
            order);
    assertThat(audits).hasSize(1);
    assertThat(audits.getFirst())
        .containsEntry("event_type", "JOB_ORDER_CANCELLED_VIA_CLIENT")
        .containsEntry("client_id", world.getClient())
        .containsEntry("actor_user_id", logistician)
        .containsEntry("details", "previousStatus=OPEN");
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM audit_event WHERE subject_id = ?"
                    + " AND event_type <> 'JOB_ORDER_CREATED_VIA_CLIENT'",
                String.class,
                refusedOrder))
        .isEmpty();
  }

  @Test
  void theFinishedNoticeNamesTheApplicationAndSkipsTheMember() throws Exception {
    UUID profitLogistician = world.user("cancel-notice-profit", false);
    world.seat(profitLogistician, world.getResponsible(), "MEMBER", true);
    UUID order = world.createAuec(logistician, 250_000L);

    assertThat(cancel(logistician, order, 0L).getStatus()).isEqualTo(200);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(finishedNotices(order, profitLogistician))
                .singleElement()
                .satisfies(params -> assertThat(params).contains("VerseKit", "CANCELLED")));
    assertThat(finishedNotices(order, logistician)).isEmpty();
  }

  @Test
  void eachWriteCountsItsOutcomeAndEachRefusalItsReason() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    UUID closed = world.createAuec(logistician, 250_000L);
    close(closed, "REJECTED");
    UUID moneyStarted = world.createAuec(logistician, 250_000L);
    submitMoney(moneyStarted);
    UUID plain = world.user("cancel-metric-plain", false);
    world.seat(plain, world.getRequesting(), "MEMBER", false);
    Counter applied = writes("applied");
    Counter rejected = writes("rejected");
    double appliedBefore = applied.count();
    double rejectedBefore = rejected.count();
    Map<String, Double> before = refusalCounts();

    cancel(logistician, UUID.randomUUID(), 0L);
    cancel(plain, order, 0L);
    cancel(logistician, order, 4L);
    cancel(logistician, closed, 1L);
    cancel(logistician, moneyStarted, 0L);
    assertThat(cancel(logistician, order, 0L).getStatus()).isEqualTo(200);

    assertThat(applied.count()).isEqualTo(appliedBefore + 1);
    assertThat(rejected.count()).isEqualTo(rejectedBefore + 5);
    Map<String, Double> after = refusalCounts();
    for (String reason :
        List.of("not_found", "not_permitted", "version_conflict", "closed", "money_started")) {
      assertThat(after.get(reason)).as(reason).isEqualTo(before.get(reason) + 1);
    }
  }

  @Test
  void anInvalidResponsibilityCountsItsReason() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    leaveTheProfitHierarchy();
    Counter invalid = refusals("responsibility_invalid");
    double before = invalid.count();

    cancel(logistician, order, 0L);

    assertThat(invalid.count()).isEqualTo(before + 1);
  }

  @Test
  void theWritesSwitchClosesTheCancel() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    world.switches(true, true, false);

    assertRefused(cancel(logistician, order, 0L), 503, "JOB_ORDER_WRITES_DISABLED");
    assertThat(status(order)).isEqualTo("OPEN");
  }

  @Test
  void theReadCapabilityDoesNotOpenTheCancel() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);

    MockHttpServletResponse response =
        world
            .getMockMvc()
            .perform(
                world
                    .relayed(cancelRequest(order), logistician, ExchangeJobOrderWorld.READ)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"lifecycleVersion\":0}"))
            .andReturn()
            .getResponse();

    assertRefused(response, 403, "SCOPE_MISSING");
    assertThat(status(order)).isEqualTo("OPEN");
  }

  private @NotNull MockHttpServletResponse cancel(
      @NotNull UUID member, @NotNull UUID order, long lifecycleVersion) throws Exception {
    return world
        .getMockMvc()
        .perform(
            world
                .relayed(cancelRequest(order), member, ExchangeJobOrderWorld.WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lifecycleVersion\":" + lifecycleVersion + "}"))
        .andReturn()
        .getResponse();
  }

  private static @NotNull MockHttpServletRequestBuilder cancelRequest(@NotNull UUID order) {
    return post(ExchangeJobOrderWorld.PATH + "/" + order + "/cancel");
  }

  private @NotNull JsonNode assertRefused(
      @NotNull MockHttpServletResponse response, int status, @NotNull String code)
      throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    JsonNode problem = objectMapper.readTree(response.getContentAsString());
    assertThat(problem.get("code").asString()).isEqualTo(code);
    return problem;
  }

  private @NotNull String status(@NotNull UUID order) {
    return jdbc.queryForObject("SELECT status FROM job_order WHERE id = ?", String.class, order);
  }

  private void close(@NotNull UUID order, @NotNull String status) {
    jdbc.update(
        "UPDATE job_order SET status = ?, priority = NULL, closed_at = now(),"
            + " lifecycle_version = lifecycle_version + 1 WHERE id = ?",
        status,
        order);
  }

  /** Moves the responsible unit out of the Profit hierarchy, which invalidates its orders. */
  private void leaveTheProfitHierarchy() {
    jdbc.update(
        "UPDATE org_unit SET parent_org_unit_id = NULL WHERE id = ?", world.getResponsible());
  }

  /** Records an accepted money operation on the order, its first durable money submission. */
  private void submitMoney(@NotNull UUID order) {
    UUID holder = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order_money_holder (id, order_id, user_id, revision) VALUES (?, ?, ?, 1)",
        holder,
        order,
        logistician);
    jdbc.update(
        """
        INSERT INTO job_order_money_operation
            (id, order_id, operation_id, holder_id, actor_id, selection_kind,
             selected_accountless_name, bank_handling, amount, recorded_at)
        VALUES (?, ?, ?, ?, ?, 'ACCOUNTLESS', ?, false, ?, now())
        """,
        UUID.randomUUID(),
        order,
        UUID.randomUUID(),
        holder,
        logistician,
        HOLDER_NAME,
        MONEY);
  }

  private static void awaitQuietly(@NotNull CountDownLatch latch) {
    try {
      assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private @NotNull List<String> finishedNotices(@NotNull UUID order, @NotNull UUID recipient) {
    return jdbc.queryForList(
        "SELECT params FROM notification WHERE entity_id = ? AND recipient_user_id = ?"
            + " AND type = 'JOB_ORDER_FINISHED'",
        String.class,
        order,
        recipient);
  }

  private @NotNull Counter writes(@NotNull String outcome) {
    return meterRegistry.counter(
        ExchangeMetricNames.EXCHANGE_WRITES,
        MetricNames.TAG_CLIENT_ID,
        world.getClient(),
        ExchangeMetricNames.TAG_RESOURCE,
        "job_order",
        MetricNames.TAG_OUTCOME,
        outcome);
  }

  private @NotNull Counter refusals(@NotNull String reason) {
    return meterRegistry.counter(
        ExchangeMetricNames.EXCHANGE_JOB_ORDER_REFUSED, MetricNames.TAG_REASON, reason);
  }

  private @NotNull Map<String, Double> refusalCounts() {
    return Map.of(
        "not_found", refusals("not_found").count(),
        "not_permitted", refusals("not_permitted").count(),
        "version_conflict", refusals("version_conflict").count(),
        "closed", refusals("closed").count(),
        "money_started", refusals("money_started").count());
  }
}
