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

import static de.greluc.krt.profit.basetool.backend.exchange.web.ExchangeJobOrderWorld.PATH;
import static de.greluc.krt.profit.basetool.backend.exchange.web.ExchangeJobOrderWorld.READ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exchange.ExchangePublishedSchemas;
import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeMetricNames;
import de.greluc.krt.profit.basetool.backend.exchange.internal.JobOrderFingerprintRecomputation;
import de.greluc.krt.profit.basetool.backend.exchange.internal.JobOrderFingerprintRuns;
import de.greluc.krt.profit.basetool.backend.exchange.internal.JobOrderWindowRun;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The change feed of the member's job-order set under {@code exchange.joborders.read}, with the
 * fingerprint worker and the window run run as the scheduled tasks would (REQ-OXCH-003,
 * REQ-XCH-013): a key of the set is an item, any other key a tombstone {@code { key, removedAt }},
 * whether the relation was lost, the membership ended, the order's 7 days ended or it was deleted.
 */
@SpringBootTest
class ExchangeJobOrderFeedTest {

  private static final String PAGE_SCHEMA = "page.schema.json#/$defs/jobOrderPage";

  @Autowired private WebApplicationContext context;
  @Autowired private JobOrderFingerprintRecomputation recomputation;
  @Autowired private JobOrderWindowRun window;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MeterRegistry meterRegistry;

  private ExchangeJobOrderWorld world;
  private UUID member;
  private UUID own;
  private UUID other;
  private UUID foreign;
  private UUID material;

  @BeforeEach
  void setUp() {
    world = new ExchangeJobOrderWorld(context);
    member = world.user("feed-member", false);
    own = world.squadron("Own", world.getProfit());
    other = world.squadron("Other", world.getProfit());
    foreign = world.squadron("Foreign", world.getProfit());
    world.seat(member, own, "MEMBER", false);
    material = world.material("Feed material");
  }

  @AfterEach
  void tearDown() {
    world.close();
  }

  @Test
  void aChangedOrderReachesTheFeedAsAnItemAndThePageCountsOneFeedRead() throws Exception {
    UUID order = order(other, own);
    drain(order);
    String cursor = snapshotCursor();
    jdbc.update("UPDATE job_order SET comment = 'Now to Area18' WHERE id = ?", order);
    drain(order);
    Counter feedReads =
        meterRegistry.counter(
            ExchangeMetricNames.EXCHANGE_JOB_ORDER_READS,
            MetricNames.TAG_CLIENT_ID,
            world.getClient(),
            ExchangeMetricNames.TAG_ROUTE,
            "feed");
    double before = feedReads.count();

    JsonNode page = feed(cursor);

    assertThat(ids(page.get("items"))).containsExactly(order.toString());
    assertThat(page.get("items").get(0).get("comment").asString()).isEqualTo("Now to Area18");
    assertThat(page.get("removed")).isEmpty();
    assertThat(page.get("hasMore").asBoolean()).isFalse();
    assertThat(page.get("nextCursor").asString()).startsWith("f1.").isNotEqualTo(cursor);
    assertThat(feedReads.count()).isEqualTo(before + 1);
    assertThat(feed(page.get("nextCursor").asString()).get("items")).isEmpty();
  }

  @Test
  void aLostRelationAndAHardDeleteLookAlike() throws Exception {
    UUID moved = order(other, own);
    UUID deleted = order(other, own);
    drain(moved, deleted);
    String cursor = snapshotCursor();
    Instant start =
        Objects.requireNonNull(jdbc.queryForObject("SELECT now()", Timestamp.class)).toInstant();
    jdbc.update("UPDATE job_order SET requesting_org_unit_id = ? WHERE id = ?", foreign, moved);
    drain(moved);
    jdbc.update("DELETE FROM job_order_material WHERE job_order_id = ?", deleted);
    jdbc.update("DELETE FROM job_order WHERE id = ?", deleted);

    JsonNode page = feed(cursor);

    assertThat(page.get("items")).isEmpty();
    assertThat(keys(page.get("removed")))
        .containsExactlyInAnyOrder(moved.toString(), deleted.toString());
    for (JsonNode removed : page.get("removed")) {
      assertThat(removed.propertyNames()).containsExactlyInAnyOrder("key", "removedAt");
      assertThat(Instant.parse(removed.get("removedAt").asString())).isAfterOrEqualTo(start);
    }
  }

  @Test
  void aMembershipEndResolvesToGoneAndTheUnitsLaterEntriesStayUnread() throws Exception {
    UUID order = order(other, own);
    drain(order);
    String cursor = snapshotCursor();

    jdbc.update(
        "DELETE FROM org_unit_membership WHERE user_id = ? AND org_unit_id = ?", member, own);
    JsonNode left = feed(cursor);

    assertThat(left.get("items")).isEmpty();
    assertThat(keys(left.get("removed"))).containsExactly(order.toString());

    jdbc.update("UPDATE job_order SET comment = 'after the member left' WHERE id = ?", order);
    drain(order);
    JsonNode later = feed(left.get("nextCursor").asString());

    assertThat(later.get("items")).isEmpty();
    assertThat(later.get("removed")).isEmpty();
  }

  @Test
  void anOrderThatLeftTheUnitBeforeTheMemberDidIsGoneThoughTheClientSyncsOnlyAfterwards()
      throws Exception {
    UUID order = order(other, own);
    drain(order);
    String cursor = snapshotCursor();
    jdbc.update("UPDATE job_order SET requesting_org_unit_id = ? WHERE id = ?", foreign, order);
    drain(order);

    jdbc.update(
        "DELETE FROM org_unit_membership WHERE user_id = ? AND org_unit_id = ?", member, own);
    JsonNode page = feed(cursor);

    assertThat(page.get("items")).isEmpty();
    assertThat(keys(page.get("removed"))).containsExactly(order.toString());
  }

  @Test
  void aReopenedOrderReturnsAsAnItem() throws Exception {
    UUID order = order(other, own);
    jdbc.update(
        "UPDATE job_order SET status = 'CANCELLED', priority = NULL,"
            + " closed_at = now() - INTERVAL '30 days' WHERE id = ?",
        order);
    drain(order);
    String cursor = snapshotCursor();
    assertThat(snapshotIds()).doesNotContain(order.toString());

    jdbc.update(
        "UPDATE job_order SET status = 'OPEN', priority = 1, closed_at = NULL,"
            + " lifecycle_version = lifecycle_version + 1 WHERE id = ?",
        order);
    drain(order);
    JsonNode page = feed(cursor);

    assertThat(ids(page.get("items"))).containsExactly(order.toString());
    assertThat(page.get("items").get(0).get("status").asString()).isEqualTo("OPEN");
  }

  @Test
  void anOrderWhoseSevenDaysEndedIsGoneAfterTheWindowRun() throws Exception {
    UUID order = order(other, own);
    jdbc.update(
        "UPDATE job_order SET status = 'COMPLETED', priority = NULL,"
            + " closed_at = now() - INTERVAL '1 day' WHERE id = ?",
        order);
    drain(order);
    String cursor = snapshotCursor();
    jdbc.update(
        "UPDATE job_order SET closed_at = now() - INTERVAL '7 days 1 minute' WHERE id = ?", order);

    assertThat(feed(cursor).get("removed")).isEmpty();

    window.ageOut();
    JsonNode page = feed(cursor);

    assertThat(page.get("items")).isEmpty();
    assertThat(keys(page.get("removed"))).containsExactly(order.toString());
  }

  @Test
  void aClientsOwnWriteReturnsAsAnItem() throws Exception {
    UUID order = order(other, own);
    drain(order);
    String cursor = snapshotCursor();

    transactionTemplate.executeWithoutResult(_ -> {
      jdbc.queryForObject(
          "SELECT set_config('basetool.change_source', ?, true)",
          String.class,
          "client|" + world.getClient() + "|" + ExchangeJobOrderWorld.INSTALLATION);
      jdbc.update("UPDATE job_order SET comment = 'written by the client' WHERE id = ?", order);
    });
    drain(order);
    JsonNode page = feed(cursor);

    assertThat(ids(page.get("items"))).containsExactly(order.toString());
    assertThat(page.get("items").get(0).get("comment").asString())
        .isEqualTo("written by the client");
  }

  @Test
  void aReaderNeverPassesAnAnnouncementThatCommitsLate() throws Exception {
    UUID order = order(other, own);
    drain(order);
    String cursor = snapshotCursor();

    try (Connection racing = dataSource.getConnection()) {
      racing.setAutoCommit(false);
      try (PreparedStatement worker =
          racing.prepareStatement(
              "UPDATE exchange_job_order_fingerprint SET fingerprint = ?, changed_at = now()"
                  + " WHERE job_order_id = ?")) {
        worker.setBytes(1, new byte[32]);
        worker.setObject(2, order);
        worker.executeUpdate();
      }
      JsonNode whileRunning = feed(cursor);

      assertThat(whileRunning.get("items")).isEmpty();
      racing.commit();
      JsonNode afterCommit = feed(whileRunning.get("nextCursor").asString());

      assertThat(ids(afterCommit.get("items"))).containsExactly(order.toString());
    }
  }

  @Test
  void theFamilySwitchClosesTheFeed() throws Exception {
    String cursor = snapshotCursor();
    world.switches(true, false, true);

    world
        .getMockMvc()
        .perform(relayed(get(PATH).param("cursor", cursor)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("JOB_ORDER_EXCHANGE_DISABLED"));
  }

  private void drain(UUID... fixtureOrders) {
    JobOrderFingerprintRuns.drain(jdbc, recomputation, fixtureOrders);
  }

  private String snapshotCursor() throws Exception {
    String cursor = null;
    boolean more = true;
    while (more) {
      MockHttpServletRequestBuilder request = get(PATH);
      if (cursor != null) {
        request.param("cursor", cursor);
      }
      JsonNode page = page(request);
      more = page.get("hasMore").asBoolean();
      cursor = page.get("nextCursor").asString();
    }
    return Objects.requireNonNull(cursor);
  }

  private List<String> snapshotIds() throws Exception {
    return ids(page(get(PATH)).get("items"));
  }

  private JsonNode feed(String cursor) throws Exception {
    return page(get(PATH).param("cursor", cursor));
  }

  private JsonNode page(MockHttpServletRequestBuilder request) throws Exception {
    String body =
        world
            .getMockMvc()
            .perform(relayed(request))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    ExchangePublishedSchemas.assertMatches(PAGE_SCHEMA, body, "a job-order page");
    return objectMapper.readTree(body);
  }

  private static List<String> ids(JsonNode items) {
    List<String> ids = new ArrayList<>();
    items.forEach(item -> ids.add(item.get("id").asString()));
    return ids;
  }

  private static List<String> keys(JsonNode removed) {
    List<String> keys = new ArrayList<>();
    removed.forEach(entry -> keys.add(entry.get("key").asString()));
    return keys;
  }

  private @NotNull MockHttpServletRequestBuilder relayed(
      @NotNull MockHttpServletRequestBuilder request) {
    return world.relayed(request, member, READ);
  }

  private @NotNull UUID order(@NotNull UUID responsible, @NotNull UUID requesting) {
    return world
        .order()
        .requesting(requesting)
        .responsible(responsible)
        .material(material, "NONE", 4.0)
        .create();
  }
}
