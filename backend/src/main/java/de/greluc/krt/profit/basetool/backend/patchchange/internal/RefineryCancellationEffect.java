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

import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryPatchCancellation;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The refinery effect of a patch change (REQ-PATCH-006): when its option is on, the running orders
 * started before the live time end as cancelled, with the patch change as their reason.
 */
@Component
@RequiredArgsConstructor
class RefineryCancellationEffect implements PatchEffect {

  private final RefineryPatchCancellation refinery;

  @Override
  public @NotNull PatchCounts preview(@NotNull PatchChangePlan plan) {
    if (!plan.options().cancelRefineryOrders()) {
      return PatchCounts.none();
    }
    return PatchCounts.of(
        PatchCount.REFINERY_ORDERS_CANCELED, refinery.countCancellable(plan.liveAt()));
  }

  @Override
  public @NotNull PatchCounts apply(@NotNull PatchChangeRun run) {
    if (!run.plan().options().cancelRefineryOrders()) {
      return PatchCounts.none();
    }
    return PatchCounts.of(
        PatchCount.REFINERY_ORDERS_CANCELED,
        refinery.cancel(run.plan().liveAt(), run.patchChangeId(), run.label()));
  }
}
