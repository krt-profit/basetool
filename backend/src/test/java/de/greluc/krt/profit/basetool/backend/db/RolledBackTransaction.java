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

package de.greluc.krt.profit.basetool.backend.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Runs planted DDL and the guard that must report it on one connection whose transaction is always
 * rolled back, so a guard's proof fixture never outlives its test; PostgreSQL DDL is transactional.
 */
final class RolledBackTransaction {

  /** Not instantiable. */
  private RolledBackTransaction() {}

  /**
   * Runs {@code work} on a template bound to a fresh transaction and rolls the transaction back.
   *
   * @param dataSource the pool to borrow the connection from
   * @param work the planted statements and the check over them
   * @param <T> the check's result type
   * @return what {@code work} returned
   * @throws SQLException when the connection cannot be prepared or rolled back
   */
  static <T> T run(@NotNull DataSource dataSource, @NotNull Function<JdbcTemplate, T> work)
      throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        return work.apply(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
      } finally {
        connection.rollback();
        connection.setAutoCommit(autoCommit);
      }
    }
  }
}
