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

package de.greluc.krt.profit.basetool.backend.mapper;

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.InventoryMissionAllocation;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderAllocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionAllocationDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between Inventory Item entities and DTOs. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {UserMapper.class, MaterialMapper.class, SquadronMapper.class})
public interface InventoryItemMapper {

  /**
   * Maps an {@link InventoryItem} entity to its outbound DTO. The two quantity splits ({@code
   * jobOrderAllocations} / {@code missionAllocations}, Variante C REQ-INV-027) map element-wise
   * through {@link #jobOrderAllocationToDto(InventoryJobOrderAllocation)} / {@link
   * #missionAllocationToDto(InventoryMissionAllocation)}; the still-unallocated remainder per
   * dimension is computed into {@code jobOrderRest} / {@code missionRest}. The allocation
   * collections and their {@code jobOrder}/{@code mission} aggregates must be initialised
   * (entity-graphed) before mapping.
   *
   * <p>After R9 Step 2 the inventory-item entity exposes {@code owningOrgUnit} (typed {@code
   * OrgUnit}); the DTO still publishes {@code owningSquadron} as {@code SquadronReferenceDto} for
   * API stability. The explicit mapping routes the source through {@code
   * SquadronMapper.orgUnitToReferenceDto}, which projects either kind — a Staffel or a
   * Spezialkommando — into the slim owner reference (id/name/shorthand), so SK-owned stock now
   * surfaces its SK badge instead of a blank cell.
   *
   * @param inventoryItem the inventory-item entity to project; {@code null} returns {@code null}.
   * @return the populated inventory-item DTO.
   */
  @Mapping(source = "jobOrder.id", target = "jobOrderId")
  @Mapping(source = "jobOrder.displayId", target = "jobOrderDisplayId")
  @Mapping(source = "mission.id", target = "missionId")
  @Mapping(source = "mission.name", target = "missionName")
  @Mapping(target = "jobOrderRest", expression = "java(jobOrderRest(inventoryItem))")
  @Mapping(target = "missionRest", expression = "java(missionRest(inventoryItem))")
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  InventoryItemDto toDto(InventoryItem inventoryItem);

  /**
   * Maps one job-order slice to its outbound chip DTO, flattening the earmarked order to its id and
   * display id.
   *
   * @param allocation the job-order slice; {@code null} returns {@code null}.
   * @return the populated slice DTO.
   */
  @Mapping(source = "jobOrder.id", target = "jobOrderId")
  @Mapping(source = "jobOrder.displayId", target = "jobOrderDisplayId")
  JobOrderAllocationDto jobOrderAllocationToDto(InventoryJobOrderAllocation allocation);

  /**
   * Maps one mission slice to its outbound chip DTO, flattening the earmarked mission to its id,
   * name and planned start.
   *
   * @param allocation the mission slice; {@code null} returns {@code null}.
   * @return the populated slice DTO.
   */
  @Mapping(source = "mission.id", target = "missionId")
  @Mapping(source = "mission.name", target = "missionName")
  @Mapping(source = "mission.plannedStartTime", target = "missionPlannedStartTime")
  MissionAllocationDto missionAllocationToDto(InventoryMissionAllocation allocation);

  /** Nested mapping for the item's {@link Location} (used as {@code uses} target). */
  LocationDto locationToDto(Location location);

  /**
   * The still-unallocated job-order remainder of an entry ({@code amount − Σ slice amounts}), SCU-
   * rounded; the value the UI renders as the job-order rest-chip. Never negative in a valid state
   * (over-allocation is server-rejected, REQ-INV-027) but returned as-is so a corrupt state
   * surfaces as a danger chip rather than being masked.
   *
   * @param item the entry whose job-order remainder to compute; never {@code null}.
   * @return the rounded remainder.
   */
  default Double jobOrderRest(InventoryItem item) {
    double allocated =
        item.getJobOrderAllocations().stream()
            .mapToDouble(a -> a.getAmount() == null ? 0.0 : a.getAmount())
            .sum();
    return rest(item.getAmount(), allocated);
  }

  /**
   * The still-unallocated mission remainder of an entry ({@code amount − Σ slice amounts}), SCU-
   * rounded; the value the UI renders as the mission rest-chip.
   *
   * @param item the entry whose mission remainder to compute; never {@code null}.
   * @return the rounded remainder.
   */
  default Double missionRest(InventoryItem item) {
    double allocated =
        item.getMissionAllocations().stream()
            .mapToDouble(a -> a.getAmount() == null ? 0.0 : a.getAmount())
            .sum();
    return rest(item.getAmount(), allocated);
  }

  /**
   * Computes an entry's dimension remainder, SCU-rounded so floating-point noise near zero does not
   * render as a spurious non-zero rest.
   *
   * @param amount the entry's total amount, or {@code null} (treated as 0).
   * @param allocated the summed slice amount already allocated in the dimension.
   * @return {@code amount − allocated}, SCU-rounded.
   */
  private Double rest(Double amount, double allocated) {
    double total = amount == null ? 0.0 : amount;
    return InventoryItem.roundToScuScale(total - allocated);
  }
}
