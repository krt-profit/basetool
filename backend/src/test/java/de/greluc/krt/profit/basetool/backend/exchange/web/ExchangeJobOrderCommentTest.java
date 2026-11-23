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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The comment change of an aUEC order under {@code exchange.joborders.write}, against PostgreSQL
 * through the relay: the shared rules of a client write on an existing order, the comment's own
 * version beside the contact's, the client marker, the client's own audit type without the text,
 * the web's need-change notice naming the application, and the refusal metrics (REQ-OXCH-005,
 * REQ-OXCH-008, REQ-OBS-025).
 */
@SpringBootTest
class ExchangeJobOrderCommentTest {

  private static final String WRITTEN_SCHEMA = "job-order-write.schema.json#/$defs/written";

  /** A comment one character above the web's limit of 1000. */
  private static final String TOO_LONG = "x".repeat(1001);

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
  void aLogisticianOfTheRequestingUnitChangesTheCommentAloneAndMarksItsClient() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);

    MockHttpServletResponse response = comment(logistician, order, " Deliver to Area18 ", 0L);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    String content = response.getContentAsString();
    ExchangePublishedSchemas.assertMatches(WRITTEN_SCHEMA, content, "a commented order");
    JsonNode written = objectMapper.readTree(content);
    assertThat(written.propertyNames()).containsExactlyInAnyOrder("id", "status", "versions");
    assertThat(written.get("id").asString()).isEqualTo(order.toString());
    assertThat(written.get("status").asString()).isEqualTo("OPEN");
    assertThat(written.get("versions").get("comment").asLong()).isEqualTo(1L);
    assertThat(written.get("versions").get("lifecycle").asLong()).isZero();
    assertThat(stored(order))
        .containsEntry("comment", "Deliver to Area18")
        .containsEntry("comment_version", 1L)
        .containsEntry("comment_client_id", world.getClient())
        .containsEntry("handle", "Write contact")
        .containsEntry("money_information_version", 0L);
  }

  @Test
  void aBlankCommentClearsItAndTheSameCommentWritesNothing() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    assertThat(comment(logistician, order, "Deliver to Area18", 0L).getStatus()).isEqualTo(200);

    assertThat(comment(logistician, order, "Deliver to Area18", 1L).getStatus()).isEqualTo(200);
    assertThat(stored(order)).containsEntry("comment_version", 1L);
    assertThat(audits(order)).hasSize(1);

    assertThat(comment(logistician, order, "  ", 1L).getStatus()).isEqualTo(200);
    assertThat(stored(order)).containsEntry("comment", null).containsEntry("comment_version", 2L);
  }

  @Test
  void aCompletedOrderStillTakesAComment() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    close(order, "COMPLETED");

    assertThat(comment(logistician, order, "Paid out", 0L).getStatus()).isEqualTo(200);
    assertThat(stored(order)).containsEntry("comment", "Paid out");
  }

  @Test
  void aContactChangeInTheBasetoolMeanwhileNeverConflictsWithTheComment() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    CountDownLatch changed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    MockHttpServletResponse response;
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<?> contactChange =
          pool.submit(() ->
              transactions.executeWithoutResult(_ -> {
                jobOrders.lockForScopedEdit(order);
                jdbc.update(
                    "UPDATE job_order SET handle = 'Web contact',"
                        + " money_information_version = money_information_version + 1"
                        + " WHERE id = ?",
                    order);
                changed.countDown();
                awaitQuietly(release);
              }));
      assertThat(changed.await(30, TimeUnit.SECONDS)).isTrue();
      Future<MockHttpServletResponse> commentChange =
          pool.submit(() -> comment(logistician, order, "Deliver to Area18", 0L));
      await().pollDelay(Duration.ofMillis(500)).until(() -> !commentChange.isDone());
      release.countDown();
      contactChange.get(30, TimeUnit.SECONDS);
      response = commentChange.get(30, TimeUnit.SECONDS);
    }

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
    assertThat(stored(order))
        .containsEntry("comment", "Deliver to Area18")
        .containsEntry("comment_version", 1L)
        .containsEntry("handle", "Web contact")
        .containsEntry("money_information_version", 1L);
  }

  @Test
  void aStaleCommentVersionIsAVersionConflictAlsoOnARetryAfterTheCache() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);

    assertRefused(comment(logistician, order, "Note", 2L), 409, "OPTIMISTIC_LOCK");
    assertThat(comment(logistician, order, "Note", 0L).getStatus()).isEqualTo(200);
    assertRefused(comment(logistician, order, "Note", 0L), 409, "OPTIMISTIC_LOCK");
    assertThat(stored(order)).containsEntry("comment_version", 1L);
  }

  @Test
  void aCancelledOrderIsClosedForTheComment() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    close(order, "CANCELLED");

    assertRefused(comment(logistician, order, "Note", 0L), 409, "JOB_ORDER_CLOSED");
    assertThat(stored(order)).containsEntry("comment", null).containsEntry("comment_version", 0L);
  }

  @Test
  void anUnknownAndAHiddenOrderAnswerTheSameNotFound() throws Exception {
    UUID hidden = world.createAuec(logistician, 250_000L);
    UUID stranger = world.user("comment-stranger", false);
    world.seat(stranger, world.squadron("Comment elsewhere", null), "MEMBER", true);

    JsonNode unknown =
        assertRefused(comment(stranger, UUID.randomUUID(), "Note", 0L), 404, "NOT_FOUND");
    JsonNode outside = assertRefused(comment(stranger, hidden, "Note", 0L), 404, "NOT_FOUND");

    assertThat(outside.get("title")).isEqualTo(unknown.get("title"));
    assertThat(stored(hidden)).containsEntry("comment", null);
  }

  @Test
  void aReaderAResponsibleSideWriterAndStockOrdersAreNotPermitted() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    UUID plain = world.user("comment-plain", false);
    world.seat(plain, world.getRequesting(), "MEMBER", false);
    UUID profitLogistician = world.user("comment-profit-logistician", false);
    world.seat(profitLogistician, world.getResponsible(), "MEMBER", true);
    UUID material = world.stockOrder("MATERIAL");
    UUID item = world.stockOrder("ITEM");

    assertRefused(comment(plain, order, "Note", 0L), 403, "ACCESS_DENIED");
    assertRefused(comment(profitLogistician, order, "Note", 0L), 403, "ACCESS_DENIED");
    assertRefused(comment(logistician, material, "Note", 0L), 403, "ACCESS_DENIED");
    assertRefused(comment(logistician, item, "Note", 0L), 403, "ACCESS_DENIED");
    assertThat(stored(order)).containsEntry("comment", null);
  }

  @Test
  void theRefusalsComeInTheSharedOrder() throws Exception {
    UUID plain = world.user("comment-order-plain", false);
    world.seat(plain, world.getRequesting(), "MEMBER", false);
    UUID stranger = world.user("comment-order-stranger", false);
    UUID material = world.stockOrder("MATERIAL");
    UUID cancelled = world.createAuec(logistician, 250_000L);
    close(cancelled, "CANCELLED");
    leaveTheProfitHierarchy();

    assertRefused(comment(stranger, material, "Note", 7L), 404, "NOT_FOUND");
    assertRefused(comment(plain, cancelled, "Note", 7L), 403, "ACCESS_DENIED");
    assertRefused(comment(logistician, cancelled, "Note", 7L), 409, "OPTIMISTIC_LOCK");
    assertRefused(
        comment(logistician, cancelled, "Note", 0L), 409, "JOB_ORDER_RESPONSIBILITY_INVALID");
  }

  @Test
  void aCommentAboveTheWebsLimitIsRefusedAndWritesNothing() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);

    assertThat(comment(logistician, order, TOO_LONG, 0L).getStatus()).isEqualTo(400);
    assertThat(stored(order)).containsEntry("comment", null).containsEntry("comment_version", 0L);
  }

  @Test
  void aClientsCommentRecordsItsOwnAuditTypeWithoutTheTextAndARefusalNothing() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    UUID refusedOrder = world.createAuec(logistician, 250_000L);
    assertRefused(comment(logistician, refusedOrder, "Refused", 5L), 409, "OPTIMISTIC_LOCK");

    assertThat(comment(logistician, order, "Deliver to Area18", 0L).getStatus()).isEqualTo(200);

    List<Map<String, Object>> audits = audits(order);
    assertThat(audits).hasSize(1);
    assertThat(audits.getFirst())
        .containsEntry("event_type", "JOB_ORDER_COMMENT_CHANGED_VIA_CLIENT")
        .containsEntry("client_id", world.getClient())
        .containsEntry("actor_user_id", logistician)
        .containsEntry("details", null);
    assertThat(audits(refusedOrder)).isEmpty();
  }

  @Test
  void theNeedChangeNoticeNamesTheApplicationAndSkipsTheMember() throws Exception {
    UUID profitLogistician = world.user("comment-notice-profit", false);
    world.seat(profitLogistician, world.getResponsible(), "MEMBER", true);
    UUID order = world.createAuec(logistician, 250_000L);

    assertThat(comment(logistician, order, "Deliver to Area18", 0L).getStatus()).isEqualTo(200);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() ->
            assertThat(needChangeNotices(order, profitLogistician))
                .singleElement()
                .satisfies(params -> assertThat(params).contains("VerseKit")));
    assertThat(needChangeNotices(order, logistician)).isEmpty();
  }

  @Test
  void eachWriteCountsItsOutcomeAndEachRefusalItsReason() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    UUID cancelled = world.createAuec(logistician, 250_000L);
    close(cancelled, "CANCELLED");
    UUID plain = world.user("comment-metric-plain", false);
    world.seat(plain, world.getRequesting(), "MEMBER", false);
    Counter applied = writes("applied");
    Counter rejected = writes("rejected");
    double appliedBefore = applied.count();
    double rejectedBefore = rejected.count();
    Map<String, Double> before = refusalCounts();

    comment(logistician, UUID.randomUUID(), "Note", 0L);
    comment(plain, order, "Note", 0L);
    comment(logistician, order, "Note", 4L);
    comment(logistician, cancelled, "Note", 0L);
    assertThat(comment(logistician, order, "Note", 0L).getStatus()).isEqualTo(200);

    assertThat(applied.count()).isEqualTo(appliedBefore + 1);
    assertThat(rejected.count()).isEqualTo(rejectedBefore + 4);
    Map<String, Double> after = refusalCounts();
    for (String reason : List.of("not_found", "not_permitted", "version_conflict", "closed")) {
      assertThat(after.get(reason)).as(reason).isEqualTo(before.get(reason) + 1);
    }
  }

  @Test
  void anInvalidResponsibilityAnswersItsOwnConflictAndCountsItsReason() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    leaveTheProfitHierarchy();
    Counter invalid = refusals("responsibility_invalid");
    double before = invalid.count();

    assertRefused(comment(logistician, order, "Note", 0L), 409, "JOB_ORDER_RESPONSIBILITY_INVALID");

    assertThat(invalid.count()).isEqualTo(before + 1);
    assertThat(stored(order)).containsEntry("comment", null);
  }

  @Test
  void theWritesSwitchClosesTheComment() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);
    world.switches(true, true, false);

    assertRefused(comment(logistician, order, "Note", 0L), 503, "JOB_ORDER_WRITES_DISABLED");
    assertThat(stored(order)).containsEntry("comment", null);
  }

  @Test
  void theReadCapabilityDoesNotOpenTheComment() throws Exception {
    UUID order = world.createAuec(logistician, 250_000L);

    assertRefused(
        comment(logistician, order, "Note", 0L, ExchangeJobOrderWorld.READ), 403, "SCOPE_MISSING");
    assertThat(stored(order)).containsEntry("comment", null);
  }

  private @NotNull MockHttpServletResponse comment(
      @NotNull UUID member, @NotNull UUID order, @Nullable String comment, long commentVersion)
      throws Exception {
    return comment(member, order, comment, commentVersion, ExchangeJobOrderWorld.WRITE);
  }

  private @NotNull MockHttpServletResponse comment(
      @NotNull UUID member,
      @NotNull UUID order,
      @Nullable String comment,
      long commentVersion,
      @NotNull String capabilities)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("comment", comment);
    body.put("commentVersion", commentVersion);
    return world
        .getMockMvc()
        .perform(
            world
                .relayed(
                    post(ExchangeJobOrderWorld.PATH + "/" + order + "/comment"),
                    member,
                    capabilities)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andReturn()
        .getResponse();
  }

  private @NotNull JsonNode assertRefused(
      @NotNull MockHttpServletResponse response, int status, @NotNull String code)
      throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    JsonNode problem = objectMapper.readTree(response.getContentAsString());
    assertThat(problem.get("code").asString()).isEqualTo(code);
    return problem;
  }

  private @NotNull Map<String, Object> stored(@NotNull UUID order) {
    return jdbc.queryForMap(
        "SELECT comment, comment_version, comment_client_id, handle, money_information_version"
            + " FROM job_order WHERE id = ?",
        order);
  }

  /** The audit rows of the order after its create. */
  private @NotNull List<Map<String, Object>> audits(@NotNull UUID order) {
    return jdbc.queryForList(
        "SELECT event_type, client_id, actor_user_id, details FROM audit_event"
            + " WHERE subject_id = ? AND event_type <> 'JOB_ORDER_CREATED_VIA_CLIENT'",
        order);
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

  private static void awaitQuietly(@NotNull CountDownLatch latch) {
    try {
      assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private @NotNull List<String> needChangeNotices(@NotNull UUID order, @NotNull UUID recipient) {
    return jdbc.queryForList(
        "SELECT params FROM notification WHERE entity_id = ? AND recipient_user_id = ?"
            + " AND type = 'JOB_ORDER_UPDATED_BY_REQUESTER'",
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
        "closed", refusals("closed").count());
  }
}
