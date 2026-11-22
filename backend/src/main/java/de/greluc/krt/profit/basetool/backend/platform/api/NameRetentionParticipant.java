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

import java.time.Instant;
import org.jetbrains.annotations.NotNull;

/**
 * One module's part of the name retention (REQ-DATA-047): removes the names and free texts of its
 * records whose event lies before the cutoff, while the records themselves stay.
 *
 * <p>Each implementation runs in a transaction of its own, so one area's failure leaves the others'
 * work committed.
 */
public interface NameRetentionParticipant {

  /**
   * Names the area in the job's log and in the audit details.
   *
   * @return a fixed, snake-case label without personal data
   */
  @NotNull
  String area();

  /**
   * Removes the names and free texts of every record whose event lies before the cutoff.
   *
   * @param cutoff the oldest event whose names are kept
   * @return the number of rows changed
   */
  int removeNamesBefore(@NotNull Instant cutoff);
}
