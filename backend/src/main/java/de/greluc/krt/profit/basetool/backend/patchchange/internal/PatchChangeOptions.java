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

import java.util.EnumSet;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The options of a patch change (REQ-PATCH-002): the patch kind presets them and the admin may
 * change each before the trigger.
 *
 * @param wipeGroups the selected wipe groups; a missing set reads as none
 * @param cancelRefineryOrders whether running refinery orders started before the live time end
 * @param currencyWipe whether the currency wipe runs
 * @param homeLocationPrompt whether members are asked for a new home location
 */
public record PatchChangeOptions(
    @NotNull @Unmodifiable Set<WipeGroup> wipeGroups,
    boolean cancelRefineryOrders,
    boolean currencyWipe,
    boolean homeLocationPrompt) {

  /** Copies the wipe groups, so the options never share a caller's set. */
  public PatchChangeOptions {
    wipeGroups = wipeGroups == null ? Set.of() : Set.copyOf(wipeGroups);
  }

  /**
   * Returns the options a patch kind presets.
   *
   * @param kind the patch kind
   * @return the preset: a rebuild wipes consumables, a full wipe every group; both end refinery
   *     orders and ask for a home location, neither wipes the currency
   */
  public static @NotNull PatchChangeOptions presetFor(@NotNull PatchKind kind) {
    Set<WipeGroup> groups =
        switch (kind) {
          case REBUILD -> EnumSet.of(WipeGroup.CONSUMABLES);
          case FULL_WIPE -> EnumSet.allOf(WipeGroup.class);
        };
    return new PatchChangeOptions(groups, true, false, true);
  }
}
