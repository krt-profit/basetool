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
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between Inventory Item entities and DTOs. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {UserMapper.class, MaterialMapper.class, SquadronMapper.class})
public abstract class InventoryItemMapper {

  // MapStruct generates a concrete subclass whose constructor takes no arguments, so the seam comes
  // in by field injection — the same shape as MissionMapper and UserMapper. The mapper depends only
  // on the support-package leaf interface, never on the service layer or on SecurityContextHolder
  // (ArchUnit mapperLayerShouldNotReachIntoSecurityContext).
  @org.springframework.beans.factory.annotation.Autowired protected StockViewerAccess stockAccess;

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
   * <p>Catalog-discriminated rows (V220, REQ-INV-029): {@code material} and {@code quality} are
   * {@code null} on a game-item row and map to {@code null} DTO fields without dereferencing;
   * {@code gameItem} maps through {@link #gameItemToReferenceDto(GameItem)} and is {@code null} on
   * a material row. Exactly one of the two catalog references is populated.
   *
   * @param inventoryItem the inventory-item entity to project; {@code null} returns {@code null}.
   * @return the populated inventory-item DTO.
   */
  @Mapping(target = "jobOrderRest", expression = "java(jobOrderRest(inventoryItem))")
  @Mapping(target = "missionRest", expression = "java(missionRest(inventoryItem))")
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  @Mapping(target = "canEdit", expression = "java(resolveCanEdit(inventoryItem))")
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
   * Projects a {@link GameItem} catalogue entity into the slim Lager reference DTO (REQ-INV-029):
   * id, display name, manufacturer name and kind name. MapStruct picks this method up for the
   * {@code gameItem} field of {@link #toDto(InventoryItem)}; it is also reused directly by the
   * item-side aggregation and catalog-search reads so every surface renders the same reference
   * shape. Dereferences the lazy {@code manufacturer} association — callers must have it fetched
   * (entity graph) or be inside an open session; batch loading via the {@code Manufacturer}
   * class-level {@code @BatchSize} keeps the grouped paths free of per-row selects.
   *
   * @param gameItem the catalogue entity to project; {@code null} returns {@code null}.
   * @return the slim reference DTO, or {@code null} for a {@code null} input.
   */
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
   * The still-unallocated job-order remainder of an entry ({@code amount − Σ slice amounts}), SCU-
   * rounded; the value the UI renders as the job-order rest-chip. Never negative in a valid state
   * (over-allocation is server-rejected, REQ-INV-027) but returned as-is so a corrupt state
   * surfaces as a danger chip rather than being masked.
   *
   * @param item the entry whose job-order remainder to compute; never {@code null}.
   * @return the rounded remainder.
   */
  public Double jobOrderRest(InventoryItem item) {
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
  public Double missionRest(InventoryItem item) {
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
