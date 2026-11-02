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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.MissionAllocationDto;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionVisibility;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Anonymises the mission slices of a job order's linked stock that point at a mission the caller
 * may not read (REQ-ORDERS-029, REQ-INV-027).
 *
 * <p>The slice and its amount stay so the row's allocation sums remain traceable; the mission's
 * name and planned start are dropped. The check is the mission detail rule ({@link
 * MissionVisibility}), independent of the caller's access to the order.
 */
@Component
@RequiredArgsConstructor
public class JobOrderMissionAllocationRedactor {

  private final MissionRepository missionRepository;
  private final MissionVisibility missionVisibility;

  /**
   * Returns the rows with every mission slice of an unreadable mission anonymised.
   *
   * @param items the linked-stock rows of one order
   * @return the rows, unchanged where the caller may read every referenced mission
   */
  @NotNull
  public List<InventoryItemDto> anonymiseUnreadableMissions(@NotNull List<InventoryItemDto> items) {
    Set<UUID> missionIds =
        items.stream()
            .flatMap(item -> item.missionAllocations().stream())
            .map(MissionAllocationDto::missionId)
            .collect(Collectors.toSet());
    if (missionIds.isEmpty()) {
      return items;
    }
    Set<UUID> readable =
        missionRepository.findAllById(missionIds).stream()
            .filter(missionVisibility::canSeeMission)
            .map(Mission::getId)
            .collect(Collectors.toSet());
    return items.stream().map(item -> anonymise(item, readable)).toList();
  }

  /**
   * Anonymises one row's slices whose mission is not in {@code readable}.
   *
   * @param item the row
   * @param readable the ids of the missions the caller may read
   * @return the row with its unreadable slices reduced to id and amount
   */
  @NotNull
  private static InventoryItemDto anonymise(
      @NotNull InventoryItemDto item, @NotNull Set<UUID> readable) {
    return item.withMissionAllocations(
        item.missionAllocations().stream()
            .map(slice ->
                readable.contains(slice.missionId())
                    ? slice
                    : new MissionAllocationDto(slice.missionId(), null, null, slice.amount()))
            .toList());
  }
}
