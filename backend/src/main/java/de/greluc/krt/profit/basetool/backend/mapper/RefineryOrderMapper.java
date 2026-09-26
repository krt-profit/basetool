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

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.RefineryGood;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
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
    uses = {
      UserMapper.class,
      MaterialMapper.class,
      SquadronMapper.class,
      LocationMapper.class,
      RefiningMethodMapper.class
    })
public interface RefineryOrderMapper {
  /**
   * Maps a {@link RefineryOrder} to its full DTO, deriving {@code profit} via {@link
   * #computeProfit} and publishing the owning org unit as {@code owningSquadron}.
   *
   * @param entity the entity to project; {@code null} returns {@code null}
   * @return the refinery-order DTO
   */
  @Mapping(target = "profit", expression = "java(computeProfit(entity))")
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  @Mapping(target = "owningOrgUnitId", ignore = true)
  RefineryOrderDto toDto(RefineryOrder entity);

  /**
   * Maps one good of an order. {@code yieldBonusPercent} is a UEX enrichment the entity does not
   * carry; {@link #toDto(RefineryOrder, Map)} fills it afterwards, so it is {@code null} here.
   *
   * @param good the good to project; {@code null} returns {@code null}.
   * @return the good's DTO without its yield bonus.
   */
  @Mapping(target = "yieldBonusPercent", ignore = true)
  RefineryGoodDto toDto(RefineryGood good);

  /**
   * Maps a refinery order like {@link #toDto(RefineryOrder)} and fills {@code yieldBonusPercent} on
   * each good whose input material is in the map; others stay {@code null}.
   *
   * @param entity the refinery order, may be {@code null}
   * @param yieldByMaterialId per-material yield bonus in percent
   * @return the enriched DTO, or {@code null} when {@code entity} is {@code null}
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
   * Maps a {@link RefineryOrder} to its slim list-row DTO, with the same profit computation and the
   * owning org unit as {@code owningSquadron}.
   */
  @Mapping(target = "profit", expression = "java(computeProfit(entity))")
  @Mapping(target = "owningSquadron", source = "owningOrgUnit")
  RefineryOrderListDto toListDto(RefineryOrder entity);

  /**
   * Builds a new {@link RefineryOrder} entity from the inbound DTO. {@code owner} is owned by the
   * service (resolved from the JWT) and stripped here.
   */
  @Mapping(target = "owner", ignore = true)
  @Mapping(target = "owningOrgUnit", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  RefineryOrder toEntity(RefineryOrderDto dto);

  /**
   * Maps one inbound good. The back-reference to its order is wired by {@code RefineryOrderService}
   * when it attaches the goods; version and timestamps belong to the persistence provider.
   *
   * @param dto the inbound good; {@code null} returns {@code null}.
   * @return a transient good without its order.
   */
  @Mapping(target = "refineryOrder", ignore = true)
  @Mapping(target = "version", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  RefineryGood toEntity(RefineryGoodDto dto);

  /**
   * Computes profit as {@code oreSales − expenses − otherExpenses}, treating {@code null} values as
   * 0.
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

  /**
   * MapStruct default that resolves an incoming {@link MissionReferenceDto} to a JPA stub Mission
   * carrying only the id — {@code RefineryOrderService} reads nothing else and re-resolves the
   * managed mission by that id.
   */
  @Nullable
  default Mission missionReferenceToMission(MissionReferenceDto dto) {
    if (dto == null) {
      return null;
    }
    Mission mission = new Mission();
    mission.setId(dto.id());
    return mission;
  }
}
