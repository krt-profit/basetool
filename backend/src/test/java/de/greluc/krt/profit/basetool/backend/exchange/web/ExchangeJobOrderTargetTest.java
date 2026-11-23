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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import io.micrometer.core.instrument.Counter;
import java.math.BigDecimal;
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
 * The target change of an aUEC order under {@code exchange.joborders.write}, against PostgreSQL
 * through the relay: in both directions, only above the current net handover and decided under the
 * order locks, through the web's target command with the client on its history entry, its audit and
 * its need-change notice (REQ-OXCH-005, REQ-OXCH-007, REQ-OBS-025).
 */
@SpringBootTest
class ExchangeJobOrderTargetTest {

  private static final String ANSWER_SCHEMA = "job-order-write.schema.json#/$defs/targetChanged";

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
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
  void aLogisticianOfTheRequestingUnitRaisesTheTargetOfAnActiveOrder() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);

    MockHttpServletResponse response = changeTarget(logistician, order, 400_000L, 0L);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    String content = response.getContentAsString();
    ExchangePublishedSchemas.assertMatches(ANSWER_SCHEMA, content, "a changed target");
    JsonNode written = objectMapper.readTree(content);
    assertThat(written.propertyNames())
        .containsExactlyInAnyOrder("id", "status", "versions", "target");
    assertThat(written.get("id").asString()).isEqualTo(order.toString());
    assertThat(written.get("status").asString()).isEqualTo("OPEN");
    assertThat(written.get("target").asLong()).isEqualTo(400_000L);
    assertThat(written.get("versions").get("target").asLong()).isEqualTo(1L);
    assertThat(written.get("versions").get("lifecycle").asLong()).isZero();
    assertThat(target(order)).isEqualByComparingTo("400000");
  }

  @Test
  void theTargetIsLoweredAboveTheNetHandoverWithoutChangingTheStatus() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    hand(order, 100_000L);

    MockHttpServletResponse response = changeTarget(logistician, order, 100_001L, 0L);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    JsonNode written = objectMapper.readTree(response.getContentAsString());
    assertThat(written.get("target").asLong()).isEqualTo(100_001L);
    assertThat(written.get("status").asString()).isEqualTo("OPEN");
    assertThat(world.status(order)).isEqualTo("OPEN");
  }

  @Test
  void anInProgressOrderTakesATargetChangeToo() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    jdbc.update(
        "UPDATE job_order SET status = 'IN_PROGRESS', lifecycle_version = 1 WHERE id = ?", order);

    assertThat(changeTarget(logistician, order, 300_000L, 0L).getStatus()).isEqualTo(200);
    assertThat(target(order)).isEqualByComparingTo("300000");
    assertThat(world.status(order)).isEqualTo("IN_PROGRESS");
  }

  @Test
  void theStoredTargetAgainAnswersTheCurrentStateAndRecordsNothing() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);

    MockHttpServletResponse response = changeTarget(logistician, order, 250_000L, 0L);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    JsonNode written = objectMapper.readTree(response.getContentAsString());
    assertThat(written.get("target").asLong()).isEqualTo(250_000L);
    assertThat(written.get("versions").get("target").asLong()).isZero();
    assertThat(targetChanges(order)).isEmpty();
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM audit_event WHERE subject_id = ?"
                    + " AND event_type <> 'JOB_ORDER_CREATED_VIA_CLIENT'",
                String.class,
                order))
        .isEmpty();
  }

  @Test
  void aTargetEqualToTheNetHandoverIsTooLowAndChangesNothing() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    hand(order, 100_000L);

    world.assertRefused(
        changeTarget(logistician, order, 100_000L, 0L), 409, "JOB_ORDER_TARGET_TOO_LOW");
    world.assertRefused(
        changeTarget(logistician, order, 50_000L, 0L), 409, "JOB_ORDER_TARGET_TOO_LOW");

    assertThat(target(order)).isEqualByComparingTo("250000");
    assertThat(world.status(order)).isEqualTo("OPEN");
    assertThat(targetChanges(order)).isEmpty();
  }

  @Test
  void aCompletedOrderIsClosedAndKeepsItsTarget() throws Exception {
    UUID completed = world.createAuec(logistician, 250_000L);
    UUID cancelled = world.createAuec(logistician, 250_000L);
    world.closeOrder(completed, "COMPLETED");
    world.closeOrder(cancelled, "CANCELLED");

    world.assertRefused(
        changeTarget(logistician, completed, 400_000L, 0L), 409, "JOB_ORDER_CLOSED");
    world.assertRefused(
        changeTarget(logistician, cancelled, 400_000L, 0L), 409, "JOB_ORDER_CLOSED");

    assertThat(target(completed)).isEqualByComparingTo("250000");
    assertThat(world.status(completed)).isEqualTo("COMPLETED");
  }

  @Test
  void theRefusalsComeInTheSharedOrderAndTheTargetsOwnRuleLast() throws Exception {
    UUID plain = world.user("target-order-plain", false);
    world.seat(plain, world.getRequesting(), "MEMBER", false);
    UUID stranger = world.user("target-order-stranger", false);
    UUID material = world.stockOrder("MATERIAL");
    UUID handedOver = world.createAuec(logistician, 250_000L);
    hand(handedOver, 200_000L);
    UUID completed = world.createAuec(logistician, 250_000L);
    world.closeOrder(completed, "COMPLETED");

    world.assertRefused(changeTarget(stranger, material, 1L, 7L), 404, "NOT_FOUND");
    world.assertRefused(changeTarget(plain, handedOver, 1L, 7L), 403, "ACCESS_DENIED");
    world.assertRefused(changeTarget(logistician, material, 1L, 7L), 403, "ACCESS_DENIED");
    world.assertRefused(changeTarget(logistician, handedOver, 1L, 7L), 409, "OPTIMISTIC_LOCK");
    world.assertRefused(changeTarget(logistician, completed, 1L, 0L), 409, "JOB_ORDER_CLOSED");
    world.assertRefused(
        changeTarget(logistician, handedOver, 1L, 0L), 409, "JOB_ORDER_TARGET_TOO_LOW");
    world.leaveTheProfitHierarchy();
    world.assertRefused(
        changeTarget(logistician, handedOver, 1L, 0L), 409, "JOB_ORDER_RESPONSIBILITY_INVALID");
  }

  @Test
  void aHandoverHoldingTheOrderLockIsMeasuredBeforeTheTargetChange() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    CountDownLatch handed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    MockHttpServletResponse response;
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<?> handover =
          pool.submit(() ->
              transactions.executeWithoutResult(_ -> {
                jobOrders.lockForScopedEdit(order);
                hand(order, 200_000L);
                handed.countDown();
                ExchangeJobOrderWorld.awaitQuietly(release);
              }));
      assertThat(handed.await(30, TimeUnit.SECONDS)).isTrue();
      Future<MockHttpServletResponse> change =
          pool.submit(() -> changeTarget(logistician, order, 150_000L, 0L));
      await().pollDelay(Duration.ofMillis(500)).until(() -> !change.isDone());
      release.countDown();
      handover.get(30, TimeUnit.SECONDS);
      response = change.get(30, TimeUnit.SECONDS);
    }

    world.assertRefused(response, 409, "JOB_ORDER_TARGET_TOO_LOW");
    assertThat(target(order)).isEqualByComparingTo("250000");
    assertThat(world.status(order)).isEqualTo("OPEN");
  }

  @Test
  void aRetryAfterTheGatewaysCacheAnswersAVersionConflict() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    assertThat(changeTarget(logistician, order, 400_000L, 0L).getStatus()).isEqualTo(200);

    world.assertRefused(changeTarget(logistician, order, 400_000L, 0L), 409, "OPTIMISTIC_LOCK");
  }

  @Test
  void aClientsChangeRecordsItsOwnAuditTypeAndItsClientOnTheHistory() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    UUID refusedOrder = world.createAuec(logistician, 250_000L);
    world.assertRefused(
        changeTarget(logistician, refusedOrder, 400_000L, 5L), 409, "OPTIMISTIC_LOCK");

    assertThat(changeTarget(logistician, order, 400_000L, 0L).getStatus()).isEqualTo(200);

    List<Map<String, Object>> audits =
        jdbc.queryForList(
            "SELECT event_type, client_id, actor_user_id, details FROM audit_event"
                + " WHERE subject_id = ? AND event_type <> 'JOB_ORDER_CREATED_VIA_CLIENT'",
            order);
    assertThat(audits).hasSize(1);
    assertThat(audits.getFirst())
        .containsEntry("event_type", "JOB_ORDER_TARGET_CHANGED_VIA_CLIENT")
        .containsEntry("client_id", world.getClient())
        .containsEntry("actor_user_id", logistician)
        .containsEntry("details", "previousTarget=250000 target=400000");
    assertThat(targetChanges(order))
        .singleElement()
        .satisfies(change ->
            assertThat(change)
                .containsEntry("client_id", world.getClient())
                .containsEntry("actor_id", logistician));
    assertThat(
            jdbc.queryForList(
                "SELECT event_type FROM audit_event WHERE subject_id = ?"
                    + " AND event_type <> 'JOB_ORDER_CREATED_VIA_CLIENT'",
                String.class,
                refusedOrder))
        .isEmpty();
    assertThat(targetChanges(refusedOrder)).isEmpty();
  }

  @Test
  void theNeedChangeNoticeNamesTheApplicationAndSkipsTheMember() throws Exception {
    UUID profitLogistician = world.user("target-notice-profit", false);
    world.seat(profitLogistician, world.getResponsible(), "MEMBER", true);
    UUID order = world.createAuec(logistician, 250_000L);

    assertThat(changeTarget(logistician, order, 400_000L, 0L).getStatus()).isEqualTo(200);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(world.needChangeNotices(order, profitLogistician))
                .singleElement()
                .satisfies(params -> assertThat(params).contains("VerseKit")));
    assertThat(world.needChangeNotices(order, logistician)).isEmpty();
  }

  @Test
  void eachChangeCountsItsOutcomeAndATargetTooLowItsReason() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    UUID handedOver = world.createAuec(logistician, 250_000L);
    hand(handedOver, 100_000L);
    Counter applied = world.writes("applied");
    Counter rejected = world.writes("rejected");
    Counter tooLow = world.refusals("target_too_low");
    double appliedBefore = applied.count();
    double rejectedBefore = rejected.count();
    double tooLowBefore = tooLow.count();

    changeTarget(logistician, handedOver, 100_000L, 0L);
    changeTarget(logistician, order, 400_000L, 9L);
    assertThat(changeTarget(logistician, order, 400_000L, 0L).getStatus()).isEqualTo(200);

    assertThat(applied.count()).isEqualTo(appliedBefore + 1);
    assertThat(rejected.count()).isEqualTo(rejectedBefore + 2);
    assertThat(tooLow.count()).isEqualTo(tooLowBefore + 1);
  }

  @Test
  void theWritesSwitchClosesTheTargetChange() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    world.switches(true, true, false);

    world.assertRefused(
        changeTarget(logistician, order, 400_000L, 0L), 503, "JOB_ORDER_WRITES_DISABLED");
    assertThat(target(order)).isEqualByComparingTo("250000");
  }

  @Test
  void theReadCapabilityDoesNotOpenTheTargetChange() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);

    MockHttpServletResponse response =
        world
            .getMockMvc()
            .perform(
                world
                    .relayed(targetRequest(order), logistician, ExchangeJobOrderWorld.READ)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"target\":400000,\"targetVersion\":0}"))
            .andReturn()
            .getResponse();

    world.assertRefused(response, 403, "SCOPE_MISSING");
    assertThat(target(order)).isEqualByComparingTo("250000");
  }

  private @NotNull MockHttpServletResponse changeTarget(
      @NotNull UUID member, @NotNull UUID order, long target, long targetVersion) throws Exception {
    return world
        .getMockMvc()
        .perform(
            world
                .relayed(targetRequest(order), member, ExchangeJobOrderWorld.WRITE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"target\":" + target + ",\"targetVersion\":" + targetVersion + "}"))
        .andReturn()
        .getResponse();
  }

  private static @NotNull MockHttpServletRequestBuilder targetRequest(@NotNull UUID order) {
    return post(ExchangeJobOrderWorld.PATH + "/" + order + "/target");
  }

  private @NotNull BigDecimal target(@NotNull UUID order) {
    return jdbc.queryForObject(
        "SELECT amount FROM job_order_money_target WHERE order_id = ?", BigDecimal.class, order);
  }

  /**
   * Records a handover of the amount to an accountless recipient, which raises the order's net
   * handover by it.
   *
   * @param order the aUEC order
   * @param amount the amount handed over in whole aUEC
   */
  private void hand(@NotNull UUID order, long amount) {
    UUID holder = UUID.randomUUID();
    UUID recipient = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO job_order_money_holder (id, order_id, user_id, revision) VALUES (?, ?, ?, 1)",
        holder,
        order,
        logistician);
    jdbc.update(
        "INSERT INTO job_order_money_recipient (id, order_id, accountless_name, active)"
            + " VALUES (?, ?, 'Target recipient', true)",
        recipient,
        order);
    jdbc.update(
        """
        INSERT INTO job_order_money_operation
            (id, order_id, operation_id, holder_id, actor_id, selection_kind,
             selected_accountless_name, bank_handling, amount, recorded_at, kind, recipient_id,
             new_provision, fee_rate, fee)
        VALUES (?, ?, ?, ?, ?, 'ACCOUNTLESS', 'Target holder', false, ?, now(), 'HANDOVER', ?,
                0, 0, 0)
        """,
        UUID.randomUUID(),
        order,
        UUID.randomUUID(),
        holder,
        logistician,
        amount,
        recipient);
  }

  private @NotNull List<Map<String, Object>> targetChanges(@NotNull UUID order) {
    return jdbc.queryForList(
        "SELECT actor_id, client_id FROM job_order_money_target_change WHERE order_id = ?", order);
  }
}
