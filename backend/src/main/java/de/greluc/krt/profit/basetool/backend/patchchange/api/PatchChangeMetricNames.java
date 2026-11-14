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

package de.greluc.krt.profit.basetool.backend.patchchange.api;

/** Meter names owned by the patch change. */
public final class PatchChangeMetricNames {

  /** Prevents instantiation of the meter name holder. */
  private PatchChangeMetricNames() {}

  /**
   * Gauge {@code basetool_patch_change_relocations_pending}: members still to relocate in the open
   * home-location phase, {@code 0} when none is open (REQ-PATCH-028).
   */
  public static final String RELOCATIONS_PENDING = "basetool.patch.change.relocations.pending";
}
