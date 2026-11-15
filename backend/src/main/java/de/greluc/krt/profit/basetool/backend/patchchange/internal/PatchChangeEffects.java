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

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs every {@link PatchEffect} bean and merges what they report (REQ-PATCH-005). The effects run
 * in bean order; none sees another's counts.
 */
@Component
@RequiredArgsConstructor
class PatchChangeEffects {

  /** Every effect bean of this module. */
  private final List<PatchEffect> effects;

  /**
   * Counts what every effect would touch, inside one read-only transaction so the counts agree.
   *
   * @param plan the live time and options under preview
   * @return the merged counts
   */
  @Transactional(readOnly = true)
  public @NotNull PatchCounts preview(@NotNull PatchChangePlan plan) {
    PatchCounts counts = PatchCounts.none();
    for (PatchEffect effect : effects) {
      counts = counts.plus(effect.preview(plan));
    }
    return counts;
  }

  /**
   * Runs every effect inside the trigger's transaction; the first exception stops the rest and
   * rolls the whole patch change back.
   *
   * @param run the patch change being triggered
   * @return the merged counts
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull PatchCounts apply(@NotNull PatchChangeRun run) {
    PatchCounts counts = PatchCounts.none();
    for (PatchEffect effect : effects) {
      counts = counts.plus(effect.apply(run));
    }
    return counts;
  }
}
