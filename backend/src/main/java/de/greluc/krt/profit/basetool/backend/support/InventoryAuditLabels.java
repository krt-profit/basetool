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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.InventoryMissionAllocation;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * Shared renderers for the audit-subject strings of an inventory row, so the item-CRUD service and
 * the checkout/transfer service emit byte-identical REQ-AUDIT-001 identity snapshots instead of
 * each carrying a private copy that could drift apart.
 */
public final class InventoryAuditLabels {

  private InventoryAuditLabels() {}

  /**
   * Composes the audit subject label {@code <material|gameItem> @ location} for an inventory row
   * (REQ-AUDIT-001); a missing part renders as an em dash.
   *
   * @param item the inventory row; lazy associations must be loadable in the transaction
   * @return the label
   */
  @NotNull
  public static String label(InventoryItem item) {
    String mat;
    if (item.getMaterial() != null) {
      mat = item.getMaterial().getName();
    } else if (item.getGameItem() != null) {
      mat = item.getGameItem().getName();
    } else {
      mat = "—";
    }
    String loc = item.getLocation() != null ? item.getLocation().getName() : "—";
    return mat + " @ " + loc;
  }

  /**
   * Renders an inventory row's first earmarked job order for an audit details payload.
   *
   * @param item the inventory row; allocations must be loadable in the transaction
   * @return {@code #<displayId>} of the first earmarked job order, {@code -} when none
   */
  public static String jobOrderRef(@NotNull InventoryItem item) {
    return item.getJobOrderAllocations().stream()
        .map(InventoryJobOrderAllocation::getJobOrder)
        .filter(Objects::nonNull)
        .findFirst()
        .map(jobOrder -> "#" + jobOrder.getDisplayId())
        .orElse("-");
  }

  /**
   * Renders an inventory row's first earmarked mission name for an audit details payload.
   *
   * @param item the inventory row; allocations must be loadable in the transaction
   * @return the name of the first earmarked mission, {@code -} when none
   */
  public static String missionName(@NotNull InventoryItem item) {
    return item.getMissionAllocations().stream()
        .map(InventoryMissionAllocation::getMission)
        .filter(Objects::nonNull)
        .findFirst()
        .map(mission -> mission.getName())
        .orElse("-");
  }
}
