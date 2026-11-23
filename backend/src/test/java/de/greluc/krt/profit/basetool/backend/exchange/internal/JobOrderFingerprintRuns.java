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

import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs the job-order fingerprint worker in a test, as the scheduled task would, over the given
 * orders only: the marks of every other order, left by other tests in the shared database, are
 * dropped before each run.
 */
public final class JobOrderFingerprintRuns {

  /** The most runs one drain makes before it gives up on a mark that keeps coming back. */
  private static final int MAX_RUNS = 10;

  private JobOrderFingerprintRuns() {}

  /**
   * Recomputes the given orders until none of them is marked.
   *
   * @param jdbc the template the marks are dropped through
   * @param recomputation the worker
   * @param orders the orders whose marks are kept
   */
  public static void drain(
      @NotNull JdbcTemplate jdbc,
      @NotNull JobOrderFingerprintRecomputation recomputation,
      @NotNull UUID... orders) {
    Set<UUID> kept = Set.of(orders);
    keepOnlyMarksOf(jdbc, kept);
    for (int run = 0; run < MAX_RUNS && recomputation.recomputeMarked() > 0; run++) {
      keepOnlyMarksOf(jdbc, kept);
    }
  }

  /**
   * Drops the marks of every order but the given ones.
   *
   * @param jdbc the template the marks are dropped through
   * @param orders the orders whose marks are kept
   */
  public static void keepOnlyMarksOf(@NotNull JdbcTemplate jdbc, @NotNull Set<UUID> orders) {
    jdbc.update(
        "DELETE FROM exchange_job_order_dirty WHERE NOT (job_order_id = ANY (?))",
        (Object) orders.toArray(UUID[]::new));
  }
}
