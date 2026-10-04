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

package de.greluc.krt.profit.basetool.backend.audit.api;

import java.time.Instant;
import org.jetbrains.annotations.NotNull;

/**
 * An audit trail kept outside the audit module that the scheduled retention purge also bounds
 * (REQ-AUDIT-006, plan §5.3).
 *
 * <p>Owned by the audit module and implemented by the module that keeps the trail. The retention
 * run purges the activity-audit domains first and then every participant; a failing participant is
 * logged and does not abort the run.
 */
public interface RetentionParticipant {

  /**
   * Names the trail in the retention log line.
   *
   * @return a short English label, for example {@code the bank audit trail}
   */
  @NotNull
  String retentionLabel();

  /**
   * Whether the trail holds a row older than the cutoff; a trail without one is skipped and writes
   * no purge marker.
   *
   * @param cutoff the exclusive cutoff
   * @return {@code true} when at least one row occurred strictly before {@code cutoff}
   */
  boolean holdsRowsBefore(@NotNull Instant cutoff);

  /**
   * Deletes the trail's rows older than the cutoff in its own transaction and records the purge
   * marker the trail keeps.
   *
   * @param cutoff the exclusive cutoff
   * @return the number of rows deleted, excluding the purge marker
   */
  int purgeBefore(@NotNull Instant cutoff);
}
