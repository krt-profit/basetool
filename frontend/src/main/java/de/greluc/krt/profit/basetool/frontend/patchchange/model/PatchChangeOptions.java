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

package de.greluc.krt.profit.basetool.frontend.patchchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Mirror of the options of a patch change (REQ-PATCH-002): the kind presets them and the admin may
 * change each before the trigger.
 *
 * @param wipeGroups the selected wipe groups, never {@code null} once constructed; a missing set
 *     reads as none
 * @param cancelRefineryOrders whether running refinery orders started before the live time end
 * @param currencyWipe whether the currency wipe runs
 * @param homeLocationPrompt whether members are asked for a new home location
 */
@DtoMirror
public record PatchChangeOptions(
    Set<WipeGroup> wipeGroups,
    boolean cancelRefineryOrders,
    boolean currencyWipe,
    boolean homeLocationPrompt) {

  /** Copies the wipe groups, so the options never share a caller's set. */
  public PatchChangeOptions {
    wipeGroups = wipeGroups == null ? Set.of() : Set.copyOf(wipeGroups);
  }

  /**
   * Whether the options select a wipe group.
   *
   * @param group the wipe group
   * @return {@code true} iff the group is selected
   */
  public boolean wipes(@NotNull WipeGroup group) {
    return wipeGroups.contains(group);
  }
}
