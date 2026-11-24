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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exchange.internal.JobOrderFingerprintRecomputation;
import de.greluc.krt.profit.basetool.backend.exchange.internal.JobOrderFingerprintRuns;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The member's job-order feed after real writes on the orders, with the fingerprint worker run as
 * the scheduled task would (REQ-OXCH-003, ADR-0406): a money write, a handover, an earmark and a
 * hierarchy change that commit while the worker runs reach the feed with an entry of their own on
 * its next run, and a provision, a holder movement, a fee, a commitment and an aUEC contact change
 * add no entry.
 */
@SpringBootTest
class ExchangeJobOrderFeedWritesTest {

  private static final String TRANSFER_FEE_RATE = "operation.transfer_fee_rate";

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JobOrderFingerprintRecomputation recomputation;

  private ExchangeJobOrderWorld world;
  private UUID material;
  private UUID writer;
  private UUID provider;
  private @Nullable String feeRate;

  @BeforeEach
  void setUp() {
    world = new ExchangeJobOrderWorld(context);
    material = world.material("Feed write material");
    writer = world.user("feed-writer", false);
    world.seat(writer, world.getRequesting(), "MEMBER", false);
    provider = world.user("feed-provider", false);
    world.seat(provider, world.getRequesting(), "MEMBER", false);
    feeRate =
        jdbc.queryForObject(
            "SELECT setting_value FROM system_setting WHERE setting_key = ?",
            String.class,
            TRANSFER_FEE_RATE);
  }

  @AfterEach
  void tearDown() {
    jdbc.update(
        "UPDATE system_setting SET setting_value = ? WHERE setting_key = ?",
        feeRate,
        TRANSFER_FEE_RATE);
    world.close();
  }

  @ParameterizedTest
  @EnumSource(RacingWrite.class)
  void aWriteThatCommitsWhileTheWorkerRunsReachesTheFeedOnTheNextRun(RacingWrite write)
      throws Exception {
    UUID order =
        write == RacingWrite.MONEY
            ? world.createAuec(world.getLogistician(), 1000)
            : materialOrder();
    UUID row =
        write == RacingWrite.EARMARK
            ? world.stockRow(world.getResponsible(), writer, material)
            : null;
    drain(order);
    JsonNode snapshot = page(get(ExchangeJobOrderWorld.PATH));
    assertThat(snapshot.get("hasMore").asBoolean()).isFalse();
    assertThat(write.figure(onlyItem(snapshot, order))).isNotEqualTo(write.expected());
    jdbc.update("UPDATE job_order SET comment = 'committed before the run' WHERE id = ?", order);

    try (Connection racing = dataSource.getConnection()) {
      racing.setAutoCommit(false);
      write.apply(racing, order, world.getResponsible(), row);
      drain(order);
      racing.commit();
    }
    JsonNode runWhileOpen = feed(snapshot.get("nextCursor").asString());
    assertThat(onlyItem(runWhileOpen, order).get("comment").asString())
        .isEqualTo("committed before the run");

    drain(order);
    JsonNode nextRun = feed(runWhileOpen.get("nextCursor").asString());

    assertThat(write.figure(onlyItem(nextRun, order))).isEqualTo(write.expected());
  }

  @Test
  void moneyTheReadDoesNotCarryAddsNoEntryAndAHandoverDoes() throws Exception {
    jdbc.update(
        "UPDATE system_setting SET setting_value = '0.005' WHERE setting_key = ?",
        TRANSFER_FEE_RATE);
    UUID order = world.createAuec(world.getLogistician(), 10_000_000);
    String reserve = provide(order, member(provider), "4000000");
    drain(order);
    String cursor = snapshotCursor();

    provide(order, writerCall(), "6000000");
    collect(order, reserve, 1, "Collector");
    collect(order, reserve, 2, "Fee collector");
    world
        .getMockMvc()
        .perform(
            put(
                    "/api/v1/orders/{id}/participations/{unitId}/money-commitment",
                    order,
                    world.getResponsible())
                .with(writerCall())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("amount", "6000000"))))
        .andExpect(status().isNoContent());
    Map<String, Object> information = new HashMap<>();
    information.put("handle", "Other contact");
    information.put("comment", null);
    information.put("version", informationVersion(order));
    information.put("commentVersion", 0);
    world
        .getMockMvc()
        .perform(
            patch("/api/v1/orders/{id}/money/information", order)
                .with(writerCall())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(information)))
        .andExpect(status().isOk());
    drain(order);

    JsonNode quiet = feed(cursor);

    assertThat(quiet.get("items")).isEmpty();
    assertThat(quiet.get("removed")).isEmpty();

    move(
        order, "HANDOVER", reserve, 3, Map.of("recipientId", recipient(order), "targetVersion", 0));
    drain(order);
    JsonNode handedOver = feed(quiet.get("nextCursor").asString());

    assertThat(onlyItem(handedOver, order).get("netHandedOver").asLong()).isPositive();
  }

  /** A source write of the carried read that commits while a worker run is under way. */
  enum RacingWrite {

    /** A target change of an aUEC order: its target 1000 rises to 1001. */
    MONEY(
        "UPDATE job_order_money_target SET amount = amount + 1, version = version + 1"
            + " WHERE order_id = ?",
        item -> String.valueOf(item.get("target").asLong()),
        "1001"),

    /** A handover credited to a material line of 10 SCU. */
    HANDOVER(
        "UPDATE job_order_material SET credited_amount = 1 WHERE job_order_id = ?",
        item -> amount(item, "handedOver"),
        "1"),

    /** An earmark of 4 SCU of stock for a material line of 10 SCU. */
    EARMARK(
        "INSERT INTO inventory_item_job_order_allocation (id, inventory_item_id, job_order_id,"
            + " amount, delivered, version, created_at, updated_at)"
            + " VALUES (gen_random_uuid(), ?, ?, 4, false, 0, now(), now())",
        item -> amount(item, "open"),
        "6"),

    /** The responsible unit deactivated, which leaves the order without a valid responsibility. */
    HIERARCHY(
        "UPDATE org_unit SET active = FALSE WHERE id = ?",
        item -> String.valueOf(item.get("responsibilityInvalid").asBoolean()),
        "true");

    private final String sql;
    private final Function<JsonNode, String> figure;
    private final String expected;

    RacingWrite(String sql, Function<JsonNode, String> figure, String expected) {
      this.sql = sql;
      this.figure = figure;
      this.expected = expected;
    }

    /**
     * Applies the write on the racing connection without committing it.
     *
     * @param connection the racing connection
     * @param order the order the write is about
     * @param responsibleUnit the order's responsible unit
     * @param row the stock row an earmark takes, {@code null} for the other writes
     * @throws SQLException if the statement fails
     */
    void apply(Connection connection, UUID order, UUID responsibleUnit, @Nullable UUID row)
        throws SQLException {
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        switch (this) {
          case MONEY, HANDOVER -> statement.setObject(1, order);
          case EARMARK -> {
            statement.setObject(1, row);
            statement.setObject(2, order);
          }
          case HIERARCHY -> statement.setObject(1, responsibleUnit);
        }
        statement.executeUpdate();
      }
    }

    /**
     * Reads the figure of a feed item the write changes.
     *
     * @param item the order's feed item
     * @return the figure as text
     */
    String figure(JsonNode item) {
      return figure.apply(item);
    }

    /**
     * Reads an amount of the item's only material line without trailing zeros.
     *
     * @param item the order's feed item
     * @param field the line's amount field
     * @return the amount as plain text
     */
    private static String amount(JsonNode item, String field) {
      return item.get("materials")
          .get(0)
          .get(field)
          .decimalValue()
          .stripTrailingZeros()
          .toPlainString();
    }

    /**
     * Names the figure the item carries once the write committed.
     *
     * @return the figure as text
     */
    String expected() {
      return expected;
    }
  }

  private void drain(UUID... orders) {
    JobOrderFingerprintRuns.drain(jdbc, recomputation, orders);
  }

  private String snapshotCursor() throws Exception {
    String cursor = null;
    boolean more = true;
    while (more) {
      MockHttpServletRequestBuilder request = get(ExchangeJobOrderWorld.PATH);
      if (cursor != null) {
        request.param("cursor", cursor);
      }
      JsonNode page = page(request);
      more = page.get("hasMore").asBoolean();
      cursor = page.get("nextCursor").asString();
    }
    return Objects.requireNonNull(cursor);
  }

  private JsonNode feed(String cursor) throws Exception {
    return page(get(ExchangeJobOrderWorld.PATH).param("cursor", cursor));
  }

  private JsonNode page(MockHttpServletRequestBuilder request) throws Exception {
    String body =
        world
            .getMockMvc()
            .perform(world.relayed(request, world.getLogistician(), ExchangeJobOrderWorld.READ))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body);
  }

  /**
   * Asserts that a feed page carries the order as its only item and no tombstone.
   *
   * @param page the feed page
   * @param order the order
   * @return the order's item
   */
  private static JsonNode onlyItem(JsonNode page, UUID order) {
    assertThat(page.get("removed")).isEmpty();
    assertThat(page.get("items")).hasSize(1);
    JsonNode item = page.get("items").get(0);
    assertThat(item.get("id").asString()).isEqualTo(order.toString());
    return item;
  }

  /**
   * Inserts an open material order between the world's units with one line of 10 SCU of the test
   * material at the {@code GOOD} tier.
   *
   * @return the order's id
   */
  private UUID materialOrder() {
    return world
        .order()
        .requesting(world.getRequesting())
        .responsible(world.getResponsible())
        .material(material, "GOOD", 10.0)
        .create();
  }

  private String provide(UUID order, RequestPostProcessor caller, String amount) throws Exception {
    MvcResult result =
        world
            .getMockMvc()
            .perform(
                post("/api/v1/orders/{id}/money/provisions", order)
                    .with(caller)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of(
                                "operationId",
                                UUID.randomUUID(),
                                "amount",
                                amount,
                                "bankHandling",
                                false,
                                "holderVersion",
                                0))))
            .andExpect(status().isOk())
            .andReturn();
    return objectMapper
        .readTree(result.getResponse().getContentAsString())
        .get("holderId")
        .asString();
  }

  private void collect(UUID order, String source, int sourceVersion, String destination)
      throws Exception {
    move(
        order,
        "COLLECTION",
        source,
        sourceVersion,
        Map.of("destinationName", destination, "destinationVersion", 0));
  }

  private void move(
      UUID order, String kind, String source, int sourceVersion, Map<String, ?> destination)
      throws Exception {
    Map<String, Object> body = new HashMap<>(destination);
    body.putAll(
        Map.of(
            "operationId",
            UUID.randomUUID(),
            "kind",
            kind,
            "sourceHolderId",
            source,
            "sourceVersion",
            sourceVersion,
            "amount",
            "1000000",
            "rate",
            "0.005",
            "bankHandling",
            false));
    world
        .getMockMvc()
        .perform(
            post("/api/v1/orders/{id}/money/movements", order)
                .with(writerCall())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  private String recipient(UUID order) throws Exception {
    MvcResult result =
        world
            .getMockMvc()
            .perform(
                post("/api/v1/orders/{id}/money/recipients", order)
                    .with(writerCall())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("name", "Requester"))))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString();
  }

  private long informationVersion(UUID order) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT money_information_version FROM job_order WHERE id = ?", Long.class, order));
  }

  /**
   * A web request of the writer as a Logistician of the responsible unit.
   *
   * @return the request's authentication
   */
  private RequestPostProcessor writerCall() {
    return jwt()
        .jwt(token -> token.subject(writer.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, world.getResponsible()));
  }

  /**
   * A web request of a member of the requesting unit without a contextual authority.
   *
   * @param member the member
   * @return the request's authentication
   */
  private static @NotNull RequestPostProcessor member(@NotNull UUID member) {
    return jwt()
        .jwt(token -> token.subject(member.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }
}
