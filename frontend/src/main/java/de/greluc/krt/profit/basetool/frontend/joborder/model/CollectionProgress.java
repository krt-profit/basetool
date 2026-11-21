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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import org.jetbrains.annotations.Nullable;

/** The order overview's collection progress of one material row (REQ-ORDERS-017). */
final class CollectionProgress {

  /** A complete collection, in percent. */
  private static final double COMPLETE_PERCENT = 100.0;

  private CollectionProgress() {}

  /**
   * Returns how much of the requested amount the linked stock and the already delivered or built
   * material cover.
   *
   * @param requested the requested amount, or {@code null} when unknown
   * @param stock the linked stock counting toward the row, or {@code null} for none
   * @param done the amount already handed over or built into units, or {@code null} for none
   * @return the progress in percent, clamped to 0–100; {@code 0} when nothing is requested
   */
  static double percent(@Nullable Double requested, @Nullable Double stock, @Nullable Double done) {
    double whole = requested == null ? 0.0 : requested;
    if (whole <= 0.0) {
      return 0.0;
    }
    double covered = (stock == null ? 0.0 : stock) + (done == null ? 0.0 : done);
    return Math.clamp(covered * COMPLETE_PERCENT / whole, 0.0, COMPLETE_PERCENT);
  }
}
