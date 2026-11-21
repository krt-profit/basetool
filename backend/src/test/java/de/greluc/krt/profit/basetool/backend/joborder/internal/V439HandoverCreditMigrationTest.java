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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Verifies that V439 credits every existing handover item to one line and rebuilds each line's
 * requested amount from its remaining amount and credit (REQ-ORDERS-077, ADR-0400).
 */
@SpringBootTest
class V439HandoverCreditMigrationTest extends MaterialContributionTestSupport {

  @Autowired private DataSource dataSource;

  /**
   * Replays V439 on pre-migration rows covering every backfill rule: the highest floor met, a lower
   * tier for stock below the higher floor, the lowest floor for stock below every floor, and
   * surplus for a material without a line.
   */
  @Test
  void creditsEveryItemToOneLineAndRebuildsTheRequestedAmount() throws Exception {
    Fixture fixture = seed();
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var sql = connection.createStatement()) {
        sql.execute("DROP TABLE job_order_handover_credit");
        sql.execute(
            "ALTER TABLE job_order_material DROP CONSTRAINT chk_job_order_material_credited,"
                + " DROP COLUMN credited_amount");
        sql.execute(
            "ALTER TABLE job_order_handover_item DROP CONSTRAINT"
                + " chk_job_order_handover_item_surplus, DROP COLUMN surplus_amount");
      }
      UUID good = tier(connection, "GOOD");
      UUID none = tier(connection, "NONE");
      UUID onlyGood = material(connection);
      UUID unordered = material(connection);
      UUID goodLine =
          single(
              connection,
              "SELECT id FROM job_order_material WHERE job_order_id = ?",
              fixture.orderId());
      update(connection, "UPDATE job_order_material SET amount = 0 WHERE id = ?", goodLine);
      UUID noneLine = line(connection, fixture.orderId(), fixture.materialId(), none, 3.0);
      UUID onlyGoodLine = line(connection, fixture.orderId(), onlyGood, good, 0.0);
      UUID handover = handover(connection, fixture.orderId());
      UUID highGrade = item(connection, handover, fixture.materialId(), 700, 4.0);
      UUID belowGood = item(connection, handover, fixture.materialId(), 600, 1.0);
      UUID belowEvery = item(connection, handover, onlyGood, 100, 2.0);
      UUID withoutLine = item(connection, handover, unordered, 700, 5.0);

      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V439__job_order_handover_credit.sql"));

      assertThat(credits(connection, handover))
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  highGrade + "->" + goodLine, 4.0,
                  belowGood + "->" + noneLine, 1.0,
                  belowEvery + "->" + onlyGoodLine, 2.0));
      assertThat(surplus(connection, withoutLine)).isEqualTo(5.0);
      assertThat(surplus(connection, highGrade)).isZero();
      assertThat(requestedAndCredited(connection, goodLine)).containsExactly(4.0, 4.0);
      assertThat(requestedAndCredited(connection, noneLine)).containsExactly(4.0, 1.0);
      assertThat(requestedAndCredited(connection, onlyGoodLine)).containsExactly(2.0, 2.0);
      connection.rollback();
    }
  }

  private static @NotNull UUID tier(@NotNull Connection connection, @NotNull String code)
      throws SQLException {
    return single(connection, "SELECT id FROM quality_tier WHERE code = ?", code);
  }

  private static @NotNull UUID material(@NotNull Connection connection) throws SQLException {
    UUID id = UUID.randomUUID();
    update(
        connection,
        "INSERT INTO material (id, name, type, quantity_type, is_manual_raw_material,"
            + " is_job_order, is_visible, source_systems)"
            + " VALUES (?, ?, 'NO_REFINE', 'SCU', false, false, true, 'UEX_ONLY')",
        id,
        "V439-" + id);
    return id;
  }

  private static @NotNull UUID line(
      @NotNull Connection connection,
      @NotNull UUID orderId,
      @NotNull UUID materialId,
      @NotNull UUID tierId,
      double remaining)
      throws SQLException {
    UUID id = UUID.randomUUID();
    update(
        connection,
        "INSERT INTO job_order_material (id, job_order_id, material_id, quality_tier_id, amount,"
            + " created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, now(), now(), 0)",
        id,
        orderId,
        materialId,
        tierId,
        remaining);
    return id;
  }

  private static @NotNull UUID handover(@NotNull Connection connection, @NotNull UUID orderId)
      throws SQLException {
    UUID id = UUID.randomUUID();
    update(
        connection,
        "INSERT INTO job_order_handover (id, job_order_id, handover_time, recipient_handle,"
            + " created_at, updated_at, version) VALUES (?, ?, now(), 'recipient', now(), now(),"
            + " 0)",
        id,
        orderId);
    return id;
  }

  private static @NotNull UUID item(
      @NotNull Connection connection,
      @NotNull UUID handoverId,
      @NotNull UUID materialId,
      int quality,
      double amount)
      throws SQLException {
    UUID id = UUID.randomUUID();
    update(
        connection,
        "INSERT INTO job_order_handover_item (id, job_order_handover_id, material_id, quality,"
            + " amount, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, now(), now(), 0)",
        id,
        handoverId,
        materialId,
        quality,
        amount);
    return id;
  }

  private static @NotNull Map<String, Double> credits(
      @NotNull Connection connection, @NotNull UUID handoverId) throws SQLException {
    Map<String, Double> credits = new LinkedHashMap<>();
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT c.job_order_handover_item_id, c.job_order_material_id, c.amount"
                + " FROM job_order_handover_credit c"
                + " JOIN job_order_handover_item i ON i.id = c.job_order_handover_item_id"
                + " WHERE i.job_order_handover_id = ?")) {
      query.setObject(1, handoverId);
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          credits.put(rows.getObject(1) + "->" + rows.getObject(2), rows.getDouble(3));
        }
      }
    }
    return credits;
  }

  private static double surplus(@NotNull Connection connection, @NotNull UUID itemId)
      throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT surplus_amount FROM job_order_handover_item WHERE id = ?")) {
      query.setObject(1, itemId);
      try (ResultSet rows = query.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getDouble(1);
      }
    }
  }

  private static double @NotNull [] requestedAndCredited(
      @NotNull Connection connection, @NotNull UUID lineId) throws SQLException {
    try (PreparedStatement query =
        connection.prepareStatement(
            "SELECT amount, credited_amount FROM job_order_material WHERE id = ?")) {
      query.setObject(1, lineId);
      try (ResultSet rows = query.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return new double[] {rows.getDouble(1), rows.getDouble(2)};
      }
    }
  }

  private static @NotNull UUID single(
      @NotNull Connection connection, @NotNull String select, @NotNull Object parameter)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(select)) {
      query.setObject(1, parameter);
      try (ResultSet rows = query.executeQuery()) {
        assertThat(rows.next()).as(select).isTrue();
        return rows.getObject(1, UUID.class);
      }
    }
  }

  private static void update(
      @NotNull Connection connection, @NotNull String statement, Object @NotNull ... parameters)
      throws SQLException {
    try (PreparedStatement update = connection.prepareStatement(statement)) {
      for (int i = 0; i < parameters.length; i++) {
        update.setObject(i + 1, parameters[i]);
      }
      update.executeUpdate();
    }
  }
}
