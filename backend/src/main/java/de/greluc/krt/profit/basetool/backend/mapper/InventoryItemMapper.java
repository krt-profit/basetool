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

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.InventoryMissionAllocation;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderAllocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionAllocationDto;
import de.greluc.krt.profit.basetool.backend.support.StockViewerAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

/** MapStruct mapper between Inventory Item entities and DTOs. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {UserMapper.class, MaterialMapper.class, SquadronMapper.class})
public abstract class InventoryItemMapper {

  @Autowired protected StockViewerAccess stockAccess;

  /**
   * Resolves the caller-dependent {@code canEdit} projection of one Lager row.
   *
   * @param inventoryItem the row being mapped; {@code null} or id-less yields {@code false}.
   * @return whether the current caller may write to it.
   */
  protected boolean resolveCanEdit(InventoryItem inventoryItem) {
    return inventoryItem != null
        && inventoryItem.getId() != null
        && stockAccess.canEditInventoryItem(inventoryItem.getId());
  }

  /**
   * Maps an {@link InventoryItem} entity to its outbound DTO, including the job-order and mission
   * allocations and their per-dimension remainders (REQ-INV-027).
   *
   * <p>The owning org unit (Staffel or Spezialkommando) is published as {@code owningSquadron}.
   * Exactly one of {@code material} and {@code gameItem} is populated (REQ-INV-029). The allocation
   * collections and their aggregates must be initialised before mapping.
   *
   * @param inventoryItem the entity to project; {@code null} returns {@code null}
   * @return the inventory-item DTO
   */
  @Mapping(target = "jobOrderRest", expression = "java(jobOrderRest(inventoryItem))")
  @Mapping(target = "missionRest", expression = "java(missionRest(inventoryItem))")
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  @Mapping(target = "canEdit", expression = "java(resolveCanEdit(inventoryItem))")
  @Mapping(target = "withVersion", ignore = true)
  public abstract InventoryItemDto toDto(InventoryItem inventoryItem);

  /**
   * Maps one job-order slice to its outbound chip DTO, flattening the earmarked order to its id and
   * display id.
   *
   * @param allocation the job-order slice; {@code null} returns {@code null}.
   * @return the populated slice DTO.
   */
  @Mapping(source = "jobOrder.id", target = "jobOrderId")
  @Mapping(source = "jobOrder.displayId", target = "jobOrderDisplayId")
  public abstract JobOrderAllocationDto jobOrderAllocationToDto(
      InventoryJobOrderAllocation allocation);

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
  public abstract MissionAllocationDto missionAllocationToDto(
      InventoryMissionAllocation allocation);

  /** Nested mapping for the item's {@link Location} (used as {@code uses} target). */
  public abstract LocationDto locationToDto(Location location);

  /**
   * Projects a {@link GameItem} into the slim Lager reference DTO: id, display name, manufacturer
   * name and kind name (REQ-INV-029).
   *
   * <p>Dereferences the lazy {@code manufacturer} association, so it must be fetched or the session
   * still open.
   *
   * @param gameItem the catalogue entity; {@code null} returns {@code null}
   * @return the reference DTO, or {@code null} for a {@code null} input
   */
  @Nullable
  public InventoryGameItemReferenceDto gameItemToReferenceDto(GameItem gameItem) {
    if (gameItem == null) {
      return null;
    }
    return new InventoryGameItemReferenceDto(
        gameItem.getId(),
        gameItem.getName(),
        gameItem.getManufacturer() != null ? gameItem.getManufacturer().getName() : null,
        gameItem.getKind() != null ? gameItem.getKind().name() : null);
  }

  /**
   * Computes the SCU-rounded job-order remainder of an entry ({@code amount − Σ slice amounts}).
   *
   * <p>A negative value is returned as-is so a corrupt state surfaces rather than being masked.
   *
   * @param item the entry; never {@code null}
   * @return the rounded remainder
   */
  public Double jobOrderRest(@NotNull InventoryItem item) {
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
  public Double missionRest(@NotNull InventoryItem item) {
    double allocated =
        item.getMissionAllocations().stream()
            .mapToDouble(a -> a.getAmount() == null ? 0.0 : a.getAmount())
            .sum();
    return rest(item.getAmount(), allocated);
  }

  /**
   * Computes an entry's SCU-rounded remainder in one allocation dimension.
   *
   * @param amount the entry's total amount, or {@code null} (treated as 0)
   * @param allocated the summed slice amount already allocated
   * @return {@code amount − allocated}, SCU-rounded
   */
  private Double rest(Double amount, double allocated) {
    double total = amount == null ? 0.0 : amount;
    return InventoryItem.roundToScuScale(total - allocated);
  }
}
