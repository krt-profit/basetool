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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
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
import java.util.Set;
import java.util.UUID;
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

  private static final String NO_TIER = "-";

  private final StockLevelRepository stockLevelRepository;
  private final StockLevelFigures figures;
  private final OrgUnitRepository orgUnitRepository;
  private final MaterialRepository materialRepository;
  private final QualityTierRepository qualityTierRepository;
  private final OwnerScopeService ownerScopeService;
  private final StockLevelAccessPolicy accessPolicy;
  private final AuditRecorder auditRecorder;

  /**
   * Lists the levels of the active units in the caller's Lager scope with their figures.
   *
   * @param orgUnitId only this unit, or {@code null} for every unit in scope
   * @return the levels, by unit, material and tier floor descending
   */
  @Transactional(readOnly = true)
  public @NotNull List<StockLevelDto> list(@Nullable UUID orgUnitId) {
    ScopePredicate scope = ownerScopeService.currentScopePredicate();
    return figures.toDtos(
        stockLevelRepository.findVisible(
            scope.adminAllScope(), scope.activeOrgUnitId(), scope.memberOrgUnitIds(), orgUnitId));
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
   * @param request the unit, material, tier and amounts
   * @return the new level with its figures
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the unit, material
   *     or tier is unknown, or the unit is inactive
   * @throws DuplicateEntityException when the unit already has a level for the material and tier
   * @throws BadRequestException when the amounts are invalid
   */
  @Transactional
  public @NotNull StockLevelDto create(@NotNull StockLevelCreateRequest request) {
    OrgUnit unit =
        Entities.require(
            orgUnitRepository.findById(request.orgUnitId()).filter(OrgUnit::isActive),
            "Org unit not found");
    Material material =
        Entities.require(materialRepository.findById(request.materialId()), "Material not found");
    final QualityTier tier = tierOf(request.qualityTierId());
    requireFreeKey(unit.getId(), material.getId(), request.qualityTierId(), null);
    StockLevel level = new StockLevel();
    level.setOrgUnit(unit);
    level.setMaterial(material);
    level.setQualityTier(tier);
    applyAmounts(level, request.minimum(), request.target());
    StockLevel saved = stockLevelRepository.saveAndFlush(level);
    auditRecorder.record(
        AuditEventType.MIN_STOCK_LEVEL_SET,
        saved.getId(),
        material.getName(),
        null,
        AuditDetails.of("unit", InventoryAuditLabels.orgUnitRef(unit))
            .with("material", material.getId())
            .with("tier", tierCode(tier))
            .with("minimum", saved.getMinimum())
            .with("target", saved.getTarget()));
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
   * @throws BadRequestException when the amounts are invalid
   */
  @Transactional
  public @NotNull StockLevelDto update(@NotNull UUID id, @NotNull StockLevelUpdateRequest request) {
    StockLevel level = requireVersioned(id, request.version());
    final QualityTier before = level.getQualityTier();
    final double minimumBefore = level.getMinimum();
    final double targetBefore = level.getTarget();
    QualityTier tier = tierOf(request.qualityTierId());
    boolean tierChanged = !Objects.equals(idOf(before), idOf(tier));
    if (tierChanged) {
      requireFreeKey(
          level.getOrgUnit().getId(), level.getMaterial().getId(), idOf(tier), level.getId());
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
        saved.getMaterial().getName(),
        null,
        AuditDetails.of("unit", InventoryAuditLabels.orgUnitRef(saved.getOrgUnit()))
            .with("fromMinimum", minimumBefore)
            .with("toMinimum", saved.getMinimum())
            .with("fromTarget", targetBefore)
            .with("toTarget", saved.getTarget())
            .with("fromTier", tierCode(before))
            .with("toTier", tierCode(tier)));
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
        level.getMaterial().getName(),
        null,
        AuditDetails.of("unit", InventoryAuditLabels.orgUnitRef(level.getOrgUnit()))
            .with("material", level.getMaterial().getId())
            .with("tier", tierCode(level.getQualityTier()))
            .with("minimum", level.getMinimum())
            .with("target", level.getTarget()));
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

  private void requireFreeKey(
      @NotNull UUID unitId, @NotNull UUID materialId, @Nullable UUID tierId, @Nullable UUID ownId) {
    stockLevelRepository
        .findByKey(unitId, materialId, tierId)
        .filter(existing -> !existing.getId().equals(ownId))
        .ifPresent(_ -> {
          throw new DuplicateEntityException(
              "The unit already has a minimum stock level for this material and tier");
        });
  }

  private static void applyAmounts(
      @NotNull StockLevel level, double minimum, @Nullable Double target) {
    boolean whole = level.getMaterial().getQuantityType() == QuantityType.PIECE;
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

  private static @NotNull String tierCode(@Nullable QualityTier tier) {
    return tier == null ? NO_TIER : tier.getCode();
  }
}
