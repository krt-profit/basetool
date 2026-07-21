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

/**
 * Shared renderers for the audit-subject strings of an inventory row, so the item-CRUD service and
 * the checkout/transfer service emit byte-identical REQ-AUDIT-001 identity snapshots instead of
 * each carrying a private copy that could drift apart.
 */
public final class InventoryAuditLabels {

  private InventoryAuditLabels() {}

  /**
   * Composes the audit subject label for an inventory row — {@code <catalog entry> @ location}, the
   * deletion-proof identity snapshot stored on each audit event (REQ-AUDIT-001). The catalog entry
   * is the material name for a material row and the game-item name for a game-item row (V220,
   * REQ-INV-029) — without the game-item branch every reused event on an item row would log the
   * em-dash fallback. A row missing both catalog references or its location renders the affected
   * part as an em dash so the label stays well-formed for orphaned rows.
   *
   * @param item the inventory row (associations may be lazily loaded but must be within the tx)
   * @return the {@code <material|gameItem> @ location} label
   */
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
   * Renders an inventory row's job-order reference for an audit details payload. Since Variante C
   * (REQ-INV-027) the job-order link lives in the allocation table, so this reads the entry's first
   * job-order slice — exactly one during the soak.
   *
   * @param item the inventory row (allocations lazily loaded but must be within the tx)
   * @return {@code #<displayId>} of the entry's first earmarked job order, {@code -} when none
   */
  public static String jobOrderRef(InventoryItem item) {
    return item.getJobOrderAllocations().stream()
        .map(InventoryJobOrderAllocation::getJobOrder)
        .filter(Objects::nonNull)
        .findFirst()
        .map(jobOrder -> "#" + jobOrder.getDisplayId())
        .orElse("-");
  }

  /**
   * Renders an inventory row's mission reference (the mission name) for an audit details payload —
   * the mission counterpart of {@link #jobOrderRef(InventoryItem)}, reading the entry's first
   * mission slice (exactly one during the soak).
   *
   * @param item the inventory row (allocations lazily loaded but must be within the tx)
   * @return the name of the entry's first earmarked mission, {@code -} when none
   */
  public static String missionName(InventoryItem item) {
    return item.getMissionAllocations().stream()
        .map(InventoryMissionAllocation::getMission)
        .filter(Objects::nonNull)
        .findFirst()
        .map(mission -> mission.getName())
        .orElse("-");
  }
}
