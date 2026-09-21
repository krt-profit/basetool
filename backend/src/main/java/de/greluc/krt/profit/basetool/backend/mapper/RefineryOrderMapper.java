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

import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.dto.LocationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryGoodDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderListDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** MapStruct mapper between Refinery Order entities and DTOs. */
@Mapper(
    config = CentralMapperConfig.class,
    uses = {UserMapper.class, MaterialMapper.class, SquadronMapper.class})
public interface RefineryOrderMapper {
  /**
   * Maps a {@link RefineryOrder} entity to its full DTO; the {@code profit} field is derived from
   * {@link #computeProfit}.
   *
   * <p>After R9 Step 2 the refinery-order entity exposes {@code owningOrgUnit} (typed {@code
   * OrgUnit}); the DTO still publishes {@code owningSquadron} as {@code SquadronReferenceDto} for
   * API stability. The explicit mapping routes the source through {@code
   * SquadronMapper.orgUnitToReferenceDto}, which projects either kind — a Staffel or a
   * Spezialkommando — into the slim owner reference (id/name/shorthand), so SK-owned orders now
   * surface their SK badge instead of a blank cell.
   *
   * @param entity the refinery-order entity to project; {@code null} returns {@code null}.
   * @return the populated refinery-order DTO.
   */
  @Mapping(target = "profit", expression = "java(computeProfit(entity))")
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  RefineryOrderDto toDto(RefineryOrder entity);

  /**
   * Same as {@link #toDto(RefineryOrder)} but additionally fills {@code yieldBonusPercent} on every
   * good whose {@code inputMaterial} appears in {@code yieldByMaterialId}. Goods whose material is
   * not in the map stay {@code null} (caller must distinguish "no data" from "explicit zero" — a 0%
   * yield row from UEX is a perfectly valid value).
   *
   * @param entity the refinery order to map, may be {@code null}
   * @param yieldByMaterialId per-material yield bonus map (see {@code
   *     RefineryOrderService.getYieldBonusByMaterialForLocation})
   * @return enriched DTO, or {@code null} when {@code entity} is {@code null}
   */
  default RefineryOrderDto toDto(RefineryOrder entity, Map<UUID, Integer> yieldByMaterialId) {
    RefineryOrderDto base = toDto(entity);
    if (base == null
        || base.goods() == null
        || yieldByMaterialId == null
        || yieldByMaterialId.isEmpty()) {
      return base;
    }
    List<RefineryGoodDto> enriched =
        base.goods().stream().map(g -> applyYield(g, yieldByMaterialId)).toList();
    return new RefineryOrderDto(
        base.id(),
        base.owner(),
        base.location(),
        base.mission(),
        base.startedAt(),
        base.durationMinutes(),
        base.expenses(),
        base.otherExpenses(),
        base.oreSales(),
        base.profit(),
        base.refiningMethod(),
        base.status(),
        enriched,
        base.owningSquadron(),
        base.version(),
        base.owningOrgUnitId());
  }

  /**
   * Slim list-row DTO of a {@link RefineryOrder}; reuses the same profit computation. Like {@link
   * #toDto(RefineryOrder)} it routes the entity's {@code owningOrgUnit} through {@code
   * SquadronMapper.orgUnitToReferenceDto} into the DTO's {@code owningSquadron} slot — without this
   * explicit mapping the renamed source property ({@code owningOrgUnit} since R9 Step 2) no longer
   * matches the target name and the IGNORE policy would leave every list row's owner {@code null},
   * blanking the Staffel/SK column in the refinery overview.
   */
  @Mapping(target = "profit", expression = "java(computeProfit(entity))")
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  RefineryOrderListDto toListDto(RefineryOrder entity);

  /**
   * Builds a new {@link RefineryOrder} entity from the inbound DTO. {@code owner} is owned by the
   * service (resolved from the JWT) and stripped here.
   */
  @Mapping(target = "owner", ignore = true)
  RefineryOrder toEntity(RefineryOrderDto dto);

  /**
   * Computes profit/loss = oreSales - expenses - otherExpenses for the order. Null values are
   * treated as 0 so that legacy data does not trigger an NPE.
   */
  default Double computeProfit(RefineryOrder entity) {
    if (entity == null) {
      return 0d;
    }
    double sales = entity.getOreSales() != null ? entity.getOreSales() : 0d;
    double costs = entity.getExpenses() != null ? entity.getExpenses() : 0d;
    double other = entity.getOtherExpenses() != null ? entity.getOtherExpenses() : 0d;
    return sales - costs - other;
  }

  /**
   * Returns a copy of {@code good} with {@code yieldBonusPercent} set from {@code
   * yieldByMaterialId} when a row for the input material exists; the original DTO when the lookup
   * misses or {@code inputMaterial} is null. Records are immutable, hence the copy.
   */
  private static RefineryGoodDto applyYield(
      RefineryGoodDto good, Map<UUID, Integer> yieldByMaterialId) {
    if (good == null || good.inputMaterial() == null || good.inputMaterial().id() == null) {
      return good;
    }
    Integer bonus = yieldByMaterialId.get(good.inputMaterial().id());
    if (bonus == null) {
      return good;
    }
    return new RefineryGoodDto(
        good.id(),
        good.inputMaterial(),
        good.inputQuantity(),
        good.outputMaterial(),
        good.outputQuantity(),
        good.quality(),
        bonus);
  }

  /** Nested mapping for the order's {@link Location}. */
  LocationDto locationToDto(Location location);

  /**
   * MapStruct default that resolves an incoming {@link MissionDto} to a JPA stub Mission carrying
   * only the id - the persistence provider then materialises the managed instance on persist.
   */
  @Nullable
  default Mission missionDtoToMission(MissionDto dto) {
    if (dto == null) {
      return null;
    }
    Mission mission = new Mission();
    mission.setId(dto.id());
    return mission;
  }
}
