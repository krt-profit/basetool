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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.backend.exchange.ExchangeJobOrderChangeLog;
import de.greluc.krt.profit.basetool.backend.exchange.ExchangeJobOrderSeed;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeRead;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderExchangeReads;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The job-order fingerprint worker against real PostgreSQL (REQ-OXCH-003, ADR-0406): triggers mark
 * an order on every source write, the worker writes a fingerprint and its instant only when the
 * carried read changed, which alone announces the order in the change log, clears exactly the marks
 * it read, and loses no write that commits while it runs.
 */
@SpringBootTest
class JobOrderFingerprintWorkerIntegrationTest {

  @Autowired private WebApplicationContext context;
  @Autowired private JobOrderRepository orders;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private JsonMapper json;
  @Autowired private JobOrderFingerprintRecomputation recomputation;
  @Autowired private ExchangeJobOrderMarkRepository marks;
  @Autowired private JobOrderExchangeReads reads;
  @Autowired private MeterRegistry meterRegistry;

  private ExchangeJobOrderSeed seed;
  private ExchangeJobOrderChangeLog changes;
  private MockMvc mvc;
  private UUID responsible;
  private UUID requester;
  private UUID writer;
  private UUID reader;
  private UUID material;
  private String feeRate = "0";

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    seed = new ExchangeJobOrderSeed(context);
    changes = new ExchangeJobOrderChangeLog(jdbc);
    responsible = seed.squadron("Fingerprint responsible", seed.getProfit());
    requester = seed.squadron("Fingerprint requester", null);
    writer = member("fingerprint-writer");
    reader = member("fingerprint-reader");
    material = seed.material("Fingerprint material");
  }

  @AfterEach
  void clean() {
    seed.close();
  }

  @Test
  void aNewOrderGetsItsFirstFingerprintAndLosesItsMarks() {
    double changedBefore = fingerprints("changed");
    UUID order = materialOrder(JobOrderStatus.OPEN);
    assertThat(markCount(order)).isPositive();

    drain(order);

    Stored stored = stored(order);
    assertThat(stored).isNotNull();
    assertThat(stored.fingerprint()).isEqualTo(JobOrderFingerprints.of(readOf(order)));
    assertThat(stored.settlementOpen()).isFalse();
    assertThat(markCount(order)).isZero();
    assertThat(fingerprints("changed") - changedBefore).isEqualTo(1.0d);
    assertThat(recomputation.recomputeMarked()).isZero();
  }

  @Test
  void aCarriedChangeMovesTheFingerprintAndItsInstantAndAnyOtherWriteMovesNothing() {
    UUID order = materialOrder(JobOrderStatus.OPEN);
    jdbc.update(
        "INSERT INTO job_order_assignees (id, job_order_id, user_id) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        order,
        reader);
    drain(order);
    Stored first = Objects.requireNonNull(stored(order));
    double unchangedBefore = fingerprints("unchanged");

    jdbc.update(
        "UPDATE job_order_assignees SET note = 'Nur meine Notiz' WHERE job_order_id = ?", order);
    jdbc.update(
        "UPDATE job_order SET money_information_version = money_information_version + 1,"
            + " count_blueprints_with_variants = NOT count_blueprints_with_variants WHERE id = ?",
        order);
    assertThat(markCount(order)).isEqualTo(2);
    drain(order);

    assertThat(stored(order)).isEqualTo(first);
    assertThat(fingerprints("unchanged") - unchangedBefore).isEqualTo(1.0d);

    jdbc.update("UPDATE job_order SET comment = 'Bitte bis Freitag' WHERE id = ?", order);
    drain(order);

    Stored second = Objects.requireNonNull(stored(order));
    assertThat(second.fingerprint()).isNotEqualTo(first.fingerprint());
    assertThat(second.changedAt()).isAfter(first.changedAt());
  }

  @Test
  void markingAnOrderAgainKeepsTheAgeOfItsOldestMark() {
    UUID order = materialOrder(JobOrderStatus.OPEN);
    drain(order);
    jdbc.update("UPDATE job_order SET comment = 'first' WHERE id = ?", order);
    jdbc.update(
        "UPDATE exchange_job_order_dirty SET marked_at = now() - INTERVAL '10 minutes'"
            + " WHERE job_order_id = ?",
        order);
    jdbc.update("UPDATE job_order SET comment = 'second' WHERE id = ?", order);
    JobOrderFingerprintRuns.keepOnlyMarksOf(jdbc, Set.of(order));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ExchangeJobOrderBusinessGauges gauges = new ExchangeJobOrderBusinessGauges(marks);
    gauges.register(registry);

    gauges.sample();

    assertThat(registry.get("basetool.exchange.job.order.dirty.count").gauge().value())
        .isEqualTo(1.0d);
    assertThat(registry.get("basetool.exchange.job.order.dirty.oldest.age").gauge().value())
        .isGreaterThanOrEqualTo(600.0d);

    drain(order);
    gauges.sample();

    assertThat(registry.get("basetool.exchange.job.order.dirty.count").gauge().value()).isZero();
    assertThat(registry.get("basetool.exchange.job.order.dirty.oldest.age").gauge().value())
        .isZero();
  }

  @ParameterizedTest
  @EnumSource(RacingWrite.class)
  void aWriteThatCommitsWhileTheWorkerRunsIsRecomputedOnTheNextRun(RacingWrite write)
      throws Exception {
    UUID order = write == RacingWrite.MONEY ? auecOrder() : materialOrder(JobOrderStatus.OPEN);
    UUID row = write == RacingWrite.EARMARK ? seed.stockRow(responsible, writer, material) : null;
    drain(order);
    jdbc.update("UPDATE job_order SET comment = 'committed before the run' WHERE id = ?", order);

    try (Connection racing = dataSource.getConnection()) {
      racing.setAutoCommit(false);
      write.apply(racing, order, responsible, row);

      drain(order);
      Stored beforeCommit = Objects.requireNonNull(stored(order));
      assertThat(beforeCommit.fingerprint()).isEqualTo(JobOrderFingerprints.of(readOf(order)));
      long logged = changes.position();

      racing.commit();
      assertThat(markCount(order)).isPositive();
      drain(order);

      Stored afterCommit = Objects.requireNonNull(stored(order));
      assertThat(afterCommit.fingerprint())
          .isNotEqualTo(beforeCommit.fingerprint())
          .isEqualTo(JobOrderFingerprints.of(readOf(order)));
      assertThat(changes.unitsAnnounced(order, logged))
          .containsExactlyInAnyOrder(responsible, requester);
    }
  }

  @Test
  void aHierarchyChangeMarksTheClientSetAndFlipsTheResponsibility() {
    UUID active = materialOrder(JobOrderStatus.OPEN);
    UUID closedRecently = materialOrder(JobOrderStatus.COMPLETED);
    UUID closedLongAgo = materialOrder(JobOrderStatus.COMPLETED);
    UUID settling = auecOrder();
    jdbc.update(
        "UPDATE job_order SET status = 'CANCELLED', priority = NULL,"
            + " closed_at = now() - INTERVAL '30 days' WHERE id = ?",
        settling);
    jdbc.update(
        "UPDATE job_order SET closed_at = now() - INTERVAL '3 days' WHERE id = ?", closedRecently);
    jdbc.update(
        "UPDATE job_order SET closed_at = now() - INTERVAL '8 days' WHERE id = ?", closedLongAgo);
    drain(active, closedRecently, closedLongAgo, settling);
    jdbc.update(
        "UPDATE exchange_job_order_fingerprint SET settlement_open = TRUE WHERE job_order_id = ?",
        settling);
    Stored before = Objects.requireNonNull(stored(active));

    jdbc.update("UPDATE org_unit SET active = FALSE WHERE id = ?", responsible);

    assertThat(markCount(active)).isPositive();
    assertThat(markCount(closedRecently)).isPositive();
    assertThat(markCount(settling)).isPositive();
    assertThat(markCount(closedLongAgo)).isZero();
    drain(active, closedRecently, settling);
    assertThat(readOf(active).responsibilityInvalid()).isTrue();
    assertThat(Objects.requireNonNull(stored(active)).fingerprint())
        .isNotEqualTo(before.fingerprint());
  }

  @Test
  void moneyTheReadDoesNotCarryMovesNoFingerprintAndAHandoverDoes() throws Exception {
    withFeeRate("0.005");
    try {
      UUID order = createAuecOrder();
      drain(order);
      String reserve = provide(order, reader(), "4000000");
      drain(order);
      Stored inProgress = Objects.requireNonNull(stored(order));
      double unchangedBefore = fingerprints("unchanged");
      long quiet = changes.position();

      provide(order, writerCall(), "6000000");
      move(
          order,
          "COLLECTION",
          reserve,
          1,
          "1000000",
          "0.005",
          Map.of("destinationName", "Collector", "destinationVersion", 0));
      move(
          order,
          "COLLECTION",
          reserve,
          2,
          "1000000",
          "0.005",
          Map.of("destinationName", "Fee collector", "destinationVersion", 0));
      mvc.perform(
              put(
                      "/api/v1/orders/{id}/participations/{unitId}/money-commitment",
                      order,
                      responsible)
                  .with(writerCall())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json.writeValueAsString(Map.of("amount", "6000000"))))
          .andExpect(status().isNoContent());
      Map<String, Object> information = new HashMap<>();
      information.put("handle", "Other contact");
      information.put("comment", null);
      information.put("version", informationVersion(order));
      information.put("commentVersion", 0);
      mvc.perform(
              patch("/api/v1/orders/{id}/money/information", order)
                  .with(writerCall())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json.writeValueAsString(information)))
          .andExpect(status().isOk());
      drain(order);

      assertThat(stored(order)).isEqualTo(inProgress);
      assertThat(fingerprints("unchanged") - unchangedBefore).isEqualTo(1.0d);
      assertThat(changes.unitsAnnounced(order, quiet)).isEmpty();

      String recipient = recipient(order);
      move(
          order,
          "HANDOVER",
          reserve,
          3,
          "1000000",
          "0.005",
          Map.of("recipientId", recipient, "targetVersion", 0));
      drain(order);

      assertThat(Objects.requireNonNull(stored(order)).fingerprint())
          .isNotEqualTo(inProgress.fingerprint());
      assertThat(changes.unitsAnnounced(order, quiet))
          .containsExactlyInAnyOrder(responsible, requester);
    } finally {
      restoreFeeRate();
    }
  }

  @Test
  void aHardDeleteTakesTheMarksAndTheFingerprintWithTheOrder() {
    UUID order = materialOrder(JobOrderStatus.OPEN);
    drain(order);
    jdbc.update("UPDATE job_order SET comment = 'gone soon' WHERE id = ?", order);

    transactions.executeWithoutResult(_ -> orders.deleteById(order));

    assertThat(markCount(order)).isZero();
    assertThat(stored(order)).isNull();
  }

  /** A source write that commits while a worker run is under way. */
  enum RacingWrite {
    /** A target change of an aUEC order. */
    MONEY(
        "UPDATE job_order_money_target SET amount = amount + 1, version = version + 1"
            + " WHERE order_id = ?"),
    /** A handover credited to a material line. */
    HANDOVER("UPDATE job_order_material SET credited_amount = 1 WHERE job_order_id = ?"),
    /** An earmark of stock for the order. */
    EARMARK(
        "INSERT INTO inventory_item_job_order_allocation (id, inventory_item_id, job_order_id,"
            + " amount, delivered, version, created_at, updated_at)"
            + " VALUES (gen_random_uuid(), ?, ?, 4, false, 0, now(), now())"),
    /** The responsible unit leaving the Profit hierarchy. */
    HIERARCHY("UPDATE org_unit SET active = FALSE WHERE id = ?");

    private final String sql;

    RacingWrite(String sql) {
      this.sql = sql;
    }

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
  }

  /**
   * One stored fingerprint row.
   *
   * @param fingerprint the hash
   * @param changedAt its instant
   * @param settlementOpen the stored settlement flag
   */
  private record Stored(byte[] fingerprint, Instant changedAt, boolean settlementOpen) {

    @Override
    public boolean equals(Object other) {
      return other instanceof Stored that
          && Arrays.equals(fingerprint, that.fingerprint)
          && changedAt.equals(that.changedAt)
          && settlementOpen == that.settlementOpen;
    }

    @Override
    public int hashCode() {
      return 31 * Arrays.hashCode(fingerprint) + changedAt.hashCode();
    }

    @Override
    public String toString() {
      return HexFormat.of().formatHex(fingerprint) + "@" + changedAt;
    }
  }

  private void drain(UUID... fixtureOrders) {
    JobOrderFingerprintRuns.drain(jdbc, recomputation, fixtureOrders);
  }

  private int markCount(UUID order) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT count(*) FROM exchange_job_order_dirty WHERE job_order_id = ?",
            Integer.class,
            order));
  }

  private @Nullable Stored stored(UUID order) {
    return jdbc
        .query(
            "SELECT fingerprint, changed_at, settlement_open FROM exchange_job_order_fingerprint"
                + " WHERE job_order_id = ?",
            (rs, _) -> new Stored(rs.getBytes(1), rs.getTimestamp(2).toInstant(), rs.getBoolean(3)),
            order)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private JobOrderExchangeRead readOf(UUID order) {
    return Objects.requireNonNull(
        Objects.requireNonNull(transactions.execute(_ -> reads.project(Set.of(order)))).get(order));
  }

  private double fingerprints(String outcome) {
    return meterRegistry
        .get("basetool.exchange.job.order.fingerprints")
        .tag("outcome", outcome)
        .counter()
        .count();
  }

  private UUID materialOrder(JobOrderStatus status) {
    UUID order =
        seed.order()
            .requesting(requester)
            .responsible(responsible)
            .priority(status.isActive() ? 1 : null)
            .material(material, "GOOD", 10.0)
            .create();
    if (!status.isActive()) {
      jdbc.update(
          "UPDATE job_order SET status = ?, closed_at = now() WHERE id = ?", status.name(), order);
    }
    return order;
  }

  private UUID auecOrder() {
    return seed.order().requesting(requester).responsible(responsible).auec(1000).create();
  }

  private UUID createAuecOrder() throws Exception {
    MvcResult created =
        mvc.perform(
                post("/api/v1/orders/auec")
                    .with(writerCall())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "responsibleOrgUnitId",
                                responsible,
                                "requestingOrgUnitId",
                                requester,
                                "handle",
                                "Money contact",
                                "target",
                                "10000000"))))
            .andExpect(status().isCreated())
            .andReturn();
    return UUID.fromString(
        json.readTree(created.getResponse().getContentAsString()).get("id").asString());
  }

  private String provide(UUID order, RequestPostProcessor caller, String amount) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/orders/{id}/money/provisions", order)
                    .with(caller)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
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
    return json.readTree(result.getResponse().getContentAsString()).get("holderId").asString();
  }

  private void move(
      UUID order,
      String kind,
      String source,
      int sourceVersion,
      String amount,
      String rate,
      Map<String, ?> destination)
      throws Exception {
    Map<String, Object> body = new HashMap<>(destination);
    body.putAll(
        Map.of(
            "operationId", UUID.randomUUID(),
            "kind", kind,
            "sourceHolderId", source,
            "sourceVersion", sourceVersion,
            "amount", amount,
            "rate", rate,
            "bankHandling", false));
    mvc.perform(
            post("/api/v1/orders/{id}/money/movements", order)
                .with(writerCall())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  private String recipient(UUID order) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/v1/orders/{id}/money/recipients", order)
                    .with(writerCall())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", "Requester"))))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("id").asString();
  }

  private long informationVersion(UUID order) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            "SELECT money_information_version FROM job_order WHERE id = ?", Long.class, order));
  }

  private void withFeeRate(String rate) {
    feeRate =
        jdbc.queryForObject(
            "SELECT setting_value FROM system_setting WHERE setting_key = ?",
            String.class,
            "operation.transfer_fee_rate");
    jdbc.update(
        "UPDATE system_setting SET setting_value = ? WHERE setting_key = ?",
        rate,
        "operation.transfer_fee_rate");
  }

  private void restoreFeeRate() {
    jdbc.update(
        "UPDATE system_setting SET setting_value = ? WHERE setting_key = ?",
        feeRate,
        "operation.transfer_fee_rate");
  }

  private RequestPostProcessor writerCall() {
    return jwt()
        .jwt(token -> token.subject(writer.toString()))
        .authorities(
            new SimpleGrantedAuthority("ROLE_KRT_MEMBER"),
            new SimpleGrantedAuthority("ROLE_LOGISTICIAN"),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, responsible));
  }

  private RequestPostProcessor reader() {
    return jwt()
        .jwt(token -> token.subject(reader.toString()))
        .authorities(new SimpleGrantedAuthority("ROLE_KRT_MEMBER"));
  }

  private UUID member(String name) {
    UUID member = seed.user(name, false);
    seed.seat(member, requester, "MEMBER", false);
    return member;
  }
}
