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

import java.time.Duration;
import java.time.Instant;
import org.jetbrains.annotations.NotNull;

/**
 * The window of the job-order client set (REQ-OXCH-002, REQ-OXCH-010): active orders, terminal
 * orders closed within the last seven days, and aUEC orders while their fingerprint says {@code
 * settlementOpen}. The database function {@code exchange_job_order_in_window} decides the same
 * window for the feed triggers.
 */
final class JobOrderClientWindow {

  /** How long a terminal order stays in the client set after closing. */
  static final Duration CLOSED = Duration.ofDays(7);

  /**
   * The window as a JPQL predicate over the order alias {@code o} and its fingerprint {@code f},
   * bound to the parameter {@code :closedSince}.
   */
  static final String PREDICATE =
      "(o.status IN ('OPEN', 'IN_PROGRESS') OR o.closedAt >= :closedSince"
          + " OR (o.type = 'AUEC' AND f.settlementOpen = true))";

  /** Not instantiable. */
  private JobOrderClientWindow() {}

  /**
   * The earliest closing instant a terminal order is kept for at an instant.
   *
   * @param now the instant the set is decided at
   * @return {@link #CLOSED} before it
   */
  static @NotNull Instant closedSince(@NotNull Instant now) {
    return now.minus(CLOSED);
  }
}
