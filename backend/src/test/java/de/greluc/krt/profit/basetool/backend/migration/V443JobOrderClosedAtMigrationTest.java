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

package de.greluc.krt.profit.basetool.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
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
 * Verifies that V443 gives every terminal job order its last change as closing instant and that the
 * closing instant is present exactly while the status is terminal (REQ-ORDERS-054).
 */
@SpringBootTest
class V443JobOrderClosedAtMigrationTest {

  private static final Instant LAST_CHANGE = Instant.parse("2026-11-01T10:15:30.123456Z");

  /** PostgreSQL's SQLSTATE for a violated {@code CHECK} constraint. */
  private static final String CHECK_VIOLATION = "23514";

  @Autowired private DataSource dataSource;

  @Test
  void backfillsTerminalOrdersWithTheirLastChangeAndLeavesActiveOrdersOpen() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      migrateOneOrderPerStatus(connection);

      Map<String, Instant> closedAt = new HashMap<>();
      try (var sql = connection.createStatement();
          var rows = sql.executeQuery("SELECT status, closed_at FROM job_order")) {
        while (rows.next()) {
          Timestamp closed = rows.getTimestamp("closed_at");
          closedAt.put(rows.getString("status"), closed == null ? null : closed.toInstant());
        }
      }
      Map<String, Instant> expected = new HashMap<>();
      expected.put("OPEN", null);
      expected.put("IN_PROGRESS", null);
      expected.put("COMPLETED", LAST_CHANGE);
      expected.put("CANCELLED", LAST_CHANGE);
      expected.put("REJECTED", LAST_CHANGE);
      assertThat(closedAt).containsExactlyInAnyOrderEntriesOf(expected);
      connection.rollback();
    }
  }

  @Test
  void refusesATerminalOrderWithoutAClosingInstant() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      migrateOneOrderPerStatus(connection);

      assertThatThrownBy(() ->
              execute(
                  connection, "UPDATE job_order SET status = 'CANCELLED' WHERE status = 'OPEN'"))
          .isInstanceOfSatisfying(
              SQLException.class,
              failure -> assertThat(failure.getSQLState()).isEqualTo(CHECK_VIOLATION));
      connection.rollback();
    }
  }

  @Test
  void refusesAnActiveOrderWithAClosingInstant() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      migrateOneOrderPerStatus(connection);

      assertThatThrownBy(() ->
              execute(
                  connection, "UPDATE job_order SET status = 'OPEN' WHERE status = 'COMPLETED'"))
          .isInstanceOfSatisfying(
              SQLException.class,
              failure -> assertThat(failure.getSQLState()).isEqualTo(CHECK_VIOLATION));
      connection.rollback();
    }
  }

  private static void migrateOneOrderPerStatus(@NotNull Connection connection) throws SQLException {
    execute(
        connection,
        """
        CREATE TEMP TABLE job_order (
            id UUID PRIMARY KEY,
            status VARCHAR(255) NOT NULL,
            updated_at TIMESTAMP WITH TIME ZONE NOT NULL)
        """);
    for (JobOrderStatus status : JobOrderStatus.values()) {
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO job_order (id, status, updated_at) VALUES (?, ?, ?)")) {
        insert.setObject(1, UUID.randomUUID());
        insert.setString(2, status.name());
        insert.setTimestamp(3, Timestamp.from(LAST_CHANGE));
        insert.executeUpdate();
      }
    }
    ScriptUtils.executeSqlScript(
        connection, new ClassPathResource("db/migration/V443__job_order_closed_at.sql"));
  }

  private static void execute(@NotNull Connection connection, @NotNull String statement)
      throws SQLException {
    try (var sql = connection.createStatement()) {
      sql.execute(statement);
    }
  }
}
