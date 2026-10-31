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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierMapper;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLevelDemandSource;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLevelDemandSource.Request;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelAllocation.Fill;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelAllocation.Level;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelAllocation.Lot;
import de.greluc.krt.profit.basetool.backend.inventory.internal.StockLevelRepository.PoolStockRow;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitReferenceDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Computes the figures of minimum stock levels from their units' pool stock and the requested
 * amounts in one read per call and maps the levels to their DTOs (REQ-INV-067).
 */
@Component
@RequiredArgsConstructor
public class StockLevelFigures {

  private final StockLevelRepository stockLevelRepository;
  private final StockLevelAccessPolicy accessPolicy;
  private final QualityTierMapper qualityTierMapper;
  private final List<StockLevelDemandSource> demandSources;

  /** The pair a level allocation runs over: one material or game item in one unit's pool. */
  private record PoolKey(UUID unitId, UUID subjectId) {

    private static PoolKey of(@NotNull StockLevel level) {
      return new PoolKey(level.getOrgUnit().getId(), level.subjectId());
    }
  }

  /**
   * Maps levels to their DTOs with the figures of their allocation. Every level of a material in a
   * unit must be passed together, since the allocation runs over all of them.
   *
   * @param levels the levels, catalogue entries and units loaded
   * @return the DTOs in the order of {@code levels}
   */
  public @NotNull List<StockLevelDto> toDtos(@NotNull List<StockLevel> levels) {
    if (levels.isEmpty()) {
      return List.of();
    }
    Map<UUID, Fill> fills = allocate(levels);
    Map<PoolKey, List<Request>> requests = requestsOf(levels);
    Map<UUID, Boolean> editable = new HashMap<>();
    return levels.stream()
        .map(level ->
            dtoOf(
                level,
                fills,
                requestedOf(level, requests),
                editable.computeIfAbsent(
                    level.getOrgUnit().getId(), accessPolicy::canMaintainStockLevelsOf)))
        .toList();
  }

  /**
   * Maps one level to its DTO, allocating over every level of its material in its unit.
   *
   * @param level the level, catalogue entry and unit loaded
   * @return the DTO
   */
  public @NotNull StockLevelDto toDto(@NotNull StockLevel level) {
    List<StockLevel> siblings =
        stockLevelRepository.findByUnitAndSubject(level.getOrgUnit().getId(), level.subjectId());
    Map<UUID, Fill> fills = allocate(siblings);
    return dtoOf(
        level,
        fills,
        requestedOf(level, requestsOf(List.of(level))),
        accessPolicy.canMaintainStockLevelsOf(level.getOrgUnit().getId()));
  }

  private @NotNull Map<UUID, Fill> allocate(@NotNull List<StockLevel> levels) {
    Set<UUID> units = levels.stream().map(l -> l.getOrgUnit().getId()).collect(Collectors.toSet());
    Set<UUID> subjects = levels.stream().map(StockLevel::subjectId).collect(Collectors.toSet());
    Map<PoolKey, List<Lot>> lots =
        stockLevelRepository.sumPoolStock(units, subjects).stream()
            .collect(
                Collectors.groupingBy(
                    row -> new PoolKey(row.getUnitId(), row.getSubjectId()),
                    Collectors.mapping(StockLevelFigures::toLot, Collectors.toList())));
    Map<UUID, Fill> fills = new HashMap<>();
    levels.stream()
        .collect(
            Collectors.groupingBy(
                PoolKey::of, Collectors.mapping(StockLevelFigures::toLevel, Collectors.toList())))
        .forEach((key, group) ->
            fills.putAll(StockLevelAllocation.allocate(group, lots.getOrDefault(key, List.of()))));
    return fills;
  }

  private @NotNull Map<PoolKey, List<Request>> requestsOf(@NotNull List<StockLevel> levels) {
    Set<UUID> units = levels.stream().map(l -> l.getOrgUnit().getId()).collect(Collectors.toSet());
    Set<UUID> subjects = levels.stream().map(StockLevel::subjectId).collect(Collectors.toSet());
    return demandSources.stream()
        .flatMap(source -> source.outstandingRequests(units, subjects).stream())
        .collect(
            Collectors.groupingBy(request -> new PoolKey(request.unitId(), request.subjectId())));
  }

  /** Sums the requests at the level's tier floor or above; an untiered level takes them all. */
  private static double requestedOf(
      @NotNull StockLevel level, @NotNull Map<PoolKey, List<Request>> requests) {
    QualityTier tier = level.getQualityTier();
    int floor = tier == null ? 0 : tier.getMinQuality();
    return ScuAmounts.roundToScuScale(
        requests.getOrDefault(PoolKey.of(level), List.of()).stream()
            .filter(request -> request.minQuality() >= floor)
            .mapToDouble(Request::amount)
            .sum());
  }

  private static @NotNull Lot toLot(@NotNull PoolStockRow row) {
    return new Lot(
        row.getQuality(), row.getAmount(), row.getEarmarked(), row.getPersonal(), row.getStolen());
  }

  private static @NotNull Level toLevel(@NotNull StockLevel level) {
    QualityTier tier = level.getQualityTier();
    return new Level(
        level.getId(),
        tier == null ? null : tier.getMinQuality(),
        level.getMinimum(),
        level.getTarget());
  }

  private @NotNull StockLevelDto dtoOf(
      @NotNull StockLevel level,
      @NotNull Map<UUID, Fill> fills,
      double requested,
      boolean editable) {
    Fill fill = Objects.requireNonNull(fills.get(level.getId()), "every level is allocated");
    QualityTier tier = level.getQualityTier();
    OrgUnit unit = level.getOrgUnit();
    Material material = level.getMaterial();
    GameItem gameItem = level.getGameItem();
    return new StockLevelDto(
        level.getId(),
        level.getVersion(),
        new OrgUnitReferenceDto(unit.getId(), unit.getName(), unit.getShorthand(), unit.getKind()),
        material == null
            ? null
            : new MaterialReferenceDto(
                material.getId(), material.getName(), material.getQuantityType()),
        gameItem == null ? null : GameItemReferenceDto.of(gameItem),
        tier == null ? null : qualityTierMapper.toDto(tier),
        level.getMinimum(),
        level.getTarget(),
        fill.allocated(),
        fill.shortfall(),
        fill.fillRatio(),
        fill.low(),
        requested,
        editable);
  }
}
