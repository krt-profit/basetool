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

package de.greluc.krt.profit.basetool.backend.exchange;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads the job-order entries a test's writes announced in the exchange change log. */
@RequiredArgsConstructor
public final class ExchangeJobOrderChangeLog {

  /** The template the log is read through. */
  @NotNull private final JdbcTemplate jdbc;

  /**
   * Reads the log's last sequence number.
   *
   * @return the highest {@code seq}, or {@code 0} for an empty log
   */
  public long position() {
    return Objects.requireNonNull(
        jdbc.queryForObject("SELECT COALESCE(max(seq), 0) FROM exchange_change", Long.class));
  }

  /**
   * Reads the units an order was announced to after a position.
   *
   * @param order the order
   * @param after the position the entries follow
   * @return the announced units
   */
  @NotNull
  @Unmodifiable
  public Set<UUID> unitsAnnounced(@NotNull UUID order, long after) {
    return Set.copyOf(
        jdbc.queryForList(
            "SELECT DISTINCT org_unit_id FROM exchange_change WHERE seq > ?"
                + " AND resource = 'JOB_ORDER' AND entity_key = ? AND org_unit_id IS NOT NULL",
            UUID.class,
            after,
            order.toString()));
  }

  /**
   * Reads the members an order was announced to one by one after a position.
   *
   * @param order the order
   * @param after the position the entries follow
   * @return the announced members
   */
  @NotNull
  @Unmodifiable
  public Set<UUID> membersAnnounced(@NotNull UUID order, long after) {
    return Set.copyOf(
        jdbc.queryForList(
            "SELECT DISTINCT user_id FROM exchange_change WHERE seq > ?"
                + " AND resource = 'JOB_ORDER' AND entity_key = ? AND user_id IS NOT NULL",
            UUID.class,
            after,
            order.toString()));
  }

  /**
   * Reads the orders announced to one member after a position.
   *
   * @param member the member
   * @param after the position the entries follow
   * @return the announced orders' keys
   */
  @NotNull
  @Unmodifiable
  public Set<String> ordersAnnouncedTo(@NotNull UUID member, long after) {
    return Set.copyOf(
        jdbc.queryForList(
            "SELECT DISTINCT entity_key FROM exchange_change WHERE seq > ?"
                + " AND resource = 'JOB_ORDER' AND user_id = ?",
            String.class,
            after,
            member));
  }
}
