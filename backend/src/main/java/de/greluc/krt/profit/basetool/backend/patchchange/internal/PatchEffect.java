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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import org.jetbrains.annotations.NotNull;

/**
 * One central effect of a patch change (REQ-PATCH-005, ADR-0380). Every implementation is a bean of
 * this module that drives one other module through that module's api package; the trigger and the
 * preview run every bean in their own transaction, so an effect plugs in by existing.
 *
 * <p>An effect decides from the plan's options whether it applies, and touches only what was
 * created or started before the live time (ADR-0381).
 */
interface PatchEffect {

  /**
   * Counts what the effect would touch, without writing.
   *
   * @param plan the live time and options under preview
   * @return the counts, {@link PatchCounts#none()} when the effect's option is off
   */
  @NotNull
  PatchCounts preview(@NotNull PatchChangePlan plan);

  /**
   * Runs the effect inside the trigger's transaction, which also holds the patch change's record.
   * Any exception refuses the patch change as a whole: nothing of it commits.
   *
   * @param run the patch change being triggered
   * @return what the effect touched, {@link PatchCounts#none()} when its option is off
   */
  @NotNull
  PatchCounts apply(@NotNull PatchChangeRun run);
}
