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

package de.greluc.krt.profit.basetool.backend.platform.api;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * One table whose user column is re-pointed from the source account to the target on an account
 * merge (REQ-SEC-045).
 *
 * <p>The names come from the declaring module's own literals, never from a request, because they
 * are spliced into the statements.
 *
 * @param table the table name
 * @param column the column holding the {@code app_user.id}
 * @param conflictKeys the remaining columns of the unique constraint over {@code column}, or empty;
 *     a source row whose key the target already has is deleted instead of re-pointed
 */
public record OwnedRows(
    @NotNull String table, @NotNull String column, @NotNull List<String> conflictKeys) {

  /**
   * A table with no unique constraint over the user column: every row simply moves.
   *
   * @param table the table name
   * @param column the column holding the {@code app_user.id}
   * @return the declaration
   */
  public static @NotNull OwnedRows of(@NotNull String table, @NotNull String column) {
    return new OwnedRows(table, column, List.of());
  }

  /**
   * A table whose unique constraint can make the same member hold a row on both accounts.
   *
   * @param table the table name
   * @param column the column holding the {@code app_user.id}
   * @param conflictKeys the remaining columns of the unique constraint
   * @return the declaration
   */
  public static @NotNull OwnedRows deduped(
      @NotNull String table, @NotNull String column, @NotNull String... conflictKeys) {
    return new OwnedRows(table, column, List.of(conflictKeys));
  }

  /**
   * Returns the column as {@code table.column}, the form the schema catalogue and the audit row
   * report.
   *
   * @return the qualified column name
   */
  public @NotNull String qualified() {
    return table + "." + column;
  }
}
