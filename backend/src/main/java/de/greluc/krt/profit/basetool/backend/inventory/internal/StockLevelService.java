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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitReferenceDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopePredicate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists, sets, changes and removes the minimum stock levels of the units' pools (REQ-INV-067,
 * REQ-INV-068). The callers gate the writes on {@link
 * StockLevelAccessPolicy#canMaintainStockLevelsOf}; every write records its audit event in the same
 * transaction and touches no Lager row.
 */
@Service
@RequiredArgsConstructor
public class StockLevelService {

  private static final String ITEM_HAS_NO_TIER = "A game item level has no tier";

  /** Emptiest first; the stable sort keeps the unit, material and tier order among equals. */
  private static final Comparator<StockLevelDto> BY_FILL_RATIO =
      Comparator.comparingDouble(StockLevelDto::fillRatio);

  private final StockLevelRepository stockLevelRepository;
  private final StockLevelFigures figures;
  private final OrgUnitRepository orgUnitRepository;
  private final MaterialRepository materialRepository;
  private final GameItemRepository gameItemRepository;
  private final QualityTierRepository qualityTierRepository;
  private final OwnerScopeService ownerScopeService;
  private final StockLevelAccessPolicy accessPolicy;
  private final AuditRecorder auditRecorder;

  /**
   * Lists the levels of the active units in the caller's Lager scope with their figures.
   *
   * @param orgUnitId only this unit, or {@code null} for every unit in scope
   * @param lowOnly whether to keep only the low levels, lowest fill ratio first (empty levels
   *     first); every level of a material is allocated before the filter applies
   * @param limit the most levels to return, or {@code null} for all
   * @return the levels, by unit, material and tier floor descending unless {@code lowOnly}
   * @throws BadRequestException when {@code limit} is below one
   */
  @Transactional(readOnly = true)
  public @NotNull List<StockLevelDto> list(
      @Nullable UUID orgUnitId, boolean lowOnly, @Nullable Integer limit) {
    if (limit != null && limit < 1) {
      throw new BadRequestException("The limit must be at least 1");
    }
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    Stream<StockLevelDto> levels =
        figures
            .toDtos(
                stockLevelRepository.findVisible(
                    scope.adminAllScope(),
                    scope.activeOrgUnitId(),
                    scope.memberOrgUnitIds(),
                    orgUnitId))
            .stream();
    if (lowOnly) {
      levels = levels.filter(StockLevelDto::low).sorted(BY_FILL_RATIO);
    }
    return (limit == null ? levels : levels.limit(limit)).toList();
  }

  /**
   * Lists the materials and game items with at least one low level in the caller's Lager scope, for
   * the „niedrig" marker of the aggregated Lager.
   *
   * @return the material ids and the game item ids
   */
  @Transactional(readOnly = true)
  public @NotNull LowStockSubjects lowSubjects() {
    List<StockLevelDto> low = list(null, true, null);
    return new LowStockSubjects(
        low.stream()
            .map(StockLevelDto::material)
            .filter(Objects::nonNull)
            .map(MaterialReferenceDto::id)
            .collect(Collectors.toUnmodifiableSet()),
        low.stream()
            .map(StockLevelDto::gameItem)
            .filter(Objects::nonNull)
            .map(GameItemReferenceDto::id)
            .collect(Collectors.toUnmodifiableSet()));
  }

  /**
   * Lists the active units in the caller's Lager scope whose levels the caller may maintain.
   *
   * @return the units, by name
   */
  @Transactional(readOnly = true)
  public @NotNull List<OrgUnitReferenceDto> maintainableUnits() {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    List<OrgUnit> candidates;
    if (scope.adminAllScope()) {
      candidates = orgUnitRepository.findActiveSquadronsAndSpecialCommands();
    } else {
      Set<UUID> ids =
          scope.activeOrgUnitId() != null
              ? Set.of(scope.activeOrgUnitId())
              : scope.memberOrgUnitIds();
      candidates = orgUnitRepository.findAllById(ids);
    }
    return candidates.stream()
        .filter(OrgUnit::isActive)
        .filter(unit -> accessPolicy.canMaintainStockLevelsOf(unit.getId()))
        .map(unit ->
            new OrgUnitReferenceDto(
                unit.getId(), unit.getName(), unit.getShorthand(), unit.getKind()))
        .sorted(
            Comparator.comparing(
                OrgUnitReferenceDto::name, Comparator.nullsLast(String::compareTo)))
        .toList();
  }

  /**
   * Sets a new level.
   *
   * @param request the unit, material or game item, tier and amounts
   * @return the new level with its figures
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the unit, material,
   *     game item or tier is unknown, or the unit is inactive
   * @throws DuplicateEntityException when the unit already has a level for the entry and tier
   * @throws BadRequestException when not exactly one of material and game item is given, a game
   *     item carries a tier, or the amounts are invalid
   */
  @Transactional
  public @NotNull StockLevelDto create(@NotNull StockLevelCreateRequest request) {
    OrgUnit unit =
        Entities.require(
            orgUnitRepository.findById(request.orgUnitId()).filter(OrgUnit::isActive),
            "Org unit not found");
    if ((request.materialId() == null) == (request.gameItemId() == null)) {
      throw new BadRequestException("Exactly one of material and game item is required");
    }
    if (request.gameItemId() != null && request.qualityTierId() != null) {
      throw new BadRequestException(ITEM_HAS_NO_TIER);
    }
    final QualityTier tier = tierOf(request.qualityTierId());
    StockLevel level = new StockLevel();
    level.setOrgUnit(unit);
    if (request.materialId() != null) {
      Material material =
          Entities.require(materialRepository.findById(request.materialId()), "Material not found");
      requireFreeKey(
          stockLevelRepository.findByKey(unit.getId(), material.getId(), request.qualityTierId()),
          null);
      level.setMaterial(material);
    } else {
      GameItem item =
          Entities.require(gameItemRepository.findById(request.gameItemId()), "Item not found");
      requireFreeKey(stockLevelRepository.findByItemKey(unit.getId(), item.getId()), null);
      level.setGameItem(item);
    }
    level.setQualityTier(tier);
    applyAmounts(level, request.minimum(), request.target());
    StockLevel saved = stockLevelRepository.saveAndFlush(level);
    auditRecorder.record(
        AuditEventType.MIN_STOCK_LEVEL_SET,
        saved.getId(),
        saved.subjectName(),
        null,
        StockLevelAuditDetails.snapshot(saved));
    return figures.toDto(saved);
  }

  /**
   * Changes a level's minimum, target and tier; an unchanged request records no audit event.
   *
   * @param id the level
   * @param request the new values and the echoed version
   * @return the level with its figures
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the level or tier
   *     is unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   * @throws DuplicateEntityException when the new tier collides with another level
   * @throws BadRequestException when a game-item level is given a tier or the amounts are invalid
   */
  @Transactional
  public @NotNull StockLevelDto update(@NotNull UUID id, @NotNull StockLevelUpdateRequest request) {
    StockLevel level = requireVersioned(id, request.version());
    final QualityTier before = level.getQualityTier();
    final double minimumBefore = level.getMinimum();
    final double targetBefore = level.getTarget();
    if (level.getGameItem() != null && request.qualityTierId() != null) {
      throw new BadRequestException(ITEM_HAS_NO_TIER);
    }
    QualityTier tier = tierOf(request.qualityTierId());
    boolean tierChanged = !Objects.equals(idOf(before), idOf(tier));
    if (tierChanged) {
      requireFreeKey(
          stockLevelRepository.findByKey(level.getOrgUnit().getId(), level.subjectId(), idOf(tier)),
          level.getId());
    }
    level.setQualityTier(tier);
    applyAmounts(level, request.minimum(), request.target());
    boolean changed =
        tierChanged || level.getMinimum() != minimumBefore || level.getTarget() != targetBefore;
    if (!changed) {
      return figures.toDto(level);
    }
    StockLevel saved = stockLevelRepository.saveAndFlush(level);
    auditRecorder.record(
        AuditEventType.MIN_STOCK_LEVEL_CHANGED,
        saved.getId(),
        saved.subjectName(),
        null,
        StockLevelAuditDetails.unit(saved)
            .with("fromMinimum", minimumBefore)
            .with("toMinimum", saved.getMinimum())
            .with("fromTarget", targetBefore)
            .with("toTarget", saved.getTarget())
            .with("fromTier", StockLevelAuditDetails.tierCode(before))
            .with("toTier", StockLevelAuditDetails.tierCode(tier)));
    return figures.toDto(saved);
  }

  /**
   * Removes a level.
   *
   * @param id the level
   * @param version the echoed version
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the level is
   *     unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public void delete(@NotNull UUID id, long version) {
    StockLevel level = requireVersioned(id, version);
    auditRecorder.record(
        AuditEventType.MIN_STOCK_LEVEL_REMOVED,
        level.getId(),
        level.subjectName(),
        null,
        StockLevelAuditDetails.snapshot(level));
    stockLevelRepository.delete(level);
    stockLevelRepository.flush();
  }

  private @NotNull StockLevel requireVersioned(@NotNull UUID id, long version) {
    StockLevel level =
        Entities.require(stockLevelRepository.findById(id), "Minimum stock level not found");
    OptimisticLock.checkRequired(level.getVersion(), version, StockLevel.class, id);
    return level;
  }

  private @Nullable QualityTier tierOf(@Nullable UUID qualityTierId) {
    return qualityTierId == null
        ? null
        : Entities.require(qualityTierRepository.findById(qualityTierId), "Tier not found");
  }

  private static void requireFreeKey(@NotNull Optional<StockLevel> holder, @Nullable UUID ownId) {
    holder
        .filter(existing -> !existing.getId().equals(ownId))
        .ifPresent(_ -> {
          throw new DuplicateEntityException(
              "The unit already has a minimum stock level for this material and tier");
        });
  }

  private static void applyAmounts(
      @NotNull StockLevel level, double minimum, @Nullable Double target) {
    boolean whole = level.wholeUnits();
    double min = validAmount(minimum, whole);
    double tgt = target == null ? min : validAmount(target, whole);
    if (tgt < min) {
      throw new BadRequestException("The target must not be below the minimum");
    }
    level.setMinimum(min);
    level.setTarget(tgt);
  }

  private static double validAmount(double amount, boolean whole) {
    double rounded = ScuAmounts.roundToScuScale(amount);
    if (rounded <= 0.0) {
      throw new BadRequestException("The amount must be positive");
    }
    if (whole && rounded % 1 != 0) {
      throw new BadRequestException("This material is counted in whole units");
    }
    return rounded;
  }

  private static @Nullable UUID idOf(@Nullable QualityTier tier) {
    return tier == null ? null : tier.getId();
  }
}
