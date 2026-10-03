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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.EntityInUseException;
import de.greluc.krt.profit.basetool.backend.mapper.QualityTierMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.QualityTier;
import de.greluc.krt.profit.basetool.backend.model.dto.QualityTierDto;
import de.greluc.krt.profit.basetool.backend.model.dto.QualityTierWriteDto;
import de.greluc.krt.profit.basetool.backend.repository.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.support.Quality;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the quality-tier catalogue (REQ-ORDERS-036): reads it for pickers and calculations, resolves
 * a requirement's tier from the wire, and lets an administrator maintain it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QualityTierService {

  /** Orders a picker: by sort order, then by floor. */
  private static final Comparator<QualityTier> PICKER_ORDER =
      Comparator.comparingInt(QualityTier::getSortOrder)
          .thenComparingInt(QualityTier::getMinQuality);

  /** Reads and writes the catalogue. */
  private final QualityTierRepository qualityTierRepository;

  /** Maps a tier to its DTO. */
  private final QualityTierMapper qualityTierMapper;

  /** Records every catalogue change in the job-order audit domain. */
  private final AuditService auditService;

  /**
   * Lists the whole catalogue in picker order, inactive tiers included.
   *
   * @return every tier, never {@code null}
   */
  @NotNull
  public List<QualityTierDto> listAll() {
    return qualityTierRepository.findAll().stream()
        .sorted(PICKER_ORDER)
        .map(qualityTierMapper::toDto)
        .toList();
  }

  /**
   * Lists the tiers a new requirement may use, in picker order.
   *
   * @return the active tiers, never {@code null}
   */
  @NotNull
  public List<QualityTierDto> listActive() {
    return qualityTierRepository.findAll().stream()
        .filter(QualityTier::isActive)
        .sorted(PICKER_ORDER)
        .map(qualityTierMapper::toDto)
        .toList();
  }

  /**
   * Returns the "no floor" tier every stock row satisfies.
   *
   * @return the tier with floor 0
   * @throws IllegalStateException when the catalogue lacks it, which the schema forbids
   */
  @NotNull
  public QualityTier baseTier() {
    return qualityTierRepository
        .findByMinQuality(Quality.MIN)
        .orElseThrow(() -> new IllegalStateException("quality_tier has no tier with floor 0"));
  }

  /**
   * Resolves the tier a requirement names on the wire: by code, else by floor, else the base tier.
   * An inactive tier is accepted only when {@code keptTierIds} contains it, so an edit can keep
   * what an order already uses.
   *
   * @param code the tier code, or {@code null}
   * @param minQuality the floor, or {@code null}
   * @param keptTierIds tier ids the requirement already used before this write
   * @return the managed tier
   * @throws BadRequestException when nothing matches, or the match is inactive and not kept
   */
  @NotNull
  public QualityTier resolveForRequirement(
      @Nullable String code, @Nullable Integer minQuality, @NotNull Collection<UUID> keptTierIds) {
    QualityTier tier;
    if (code != null && !code.isBlank()) {
      tier =
          qualityTierRepository
              .findByCode(code.trim().toUpperCase(Locale.ROOT))
              .orElseThrow(() -> new BadRequestException("Unknown quality tier code: " + code));
    } else if (minQuality != null) {
      tier =
          qualityTierRepository
              .findByMinQuality(minQuality)
              .orElseThrow(
                  () -> new BadRequestException("No quality tier has the floor " + minQuality));
    } else {
      tier = baseTier();
    }
    if (!tier.isActive() && !keptTierIds.contains(tier.getId())) {
      throw new BadRequestException("Quality tier " + tier.getCode() + " is inactive");
    }
    return tier;
  }

  /**
   * Picks the default tier for a blueprint ingredient: the active tier with the highest floor the
   * ingredient's minimum quality still reaches.
   *
   * @param ingredientMinQuality the ingredient's minimum quality, or {@code null} for none
   * @return the default tier, the base tier when nothing higher fits
   */
  @NotNull
  public QualityTier defaultForIngredient(@Nullable Integer ingredientMinQuality) {
    int quality = Quality.orMin(ingredientMinQuality);
    return qualityTierRepository.findAllByOrderByMinQualityAsc().stream()
        .filter(QualityTier::isActive)
        .filter(t -> t.getMinQuality() <= quality)
        .max(Comparator.comparingInt(QualityTier::getMinQuality))
        .orElseGet(this::baseTier);
  }

  /**
   * Adds a tier to the catalogue.
   *
   * @param dto the new tier
   * @return the persisted tier
   * @throws DuplicateEntityException when the code or the floor is already taken
   * @throws BadRequestException when a tier with floor 0 would be inactive
   */
  @NotNull
  @Transactional
  public QualityTierDto create(@NotNull QualityTierWriteDto dto) {
    assertUnique(dto, null);
    assertBaseStaysActive(dto);
    QualityTier tier =
        QualityTier.builder()
            .code(dto.code())
            .minQuality(dto.minQuality())
            .labelDe(dto.labelDe().trim())
            .labelEn(dto.labelEn().trim())
            .sortOrder(dto.sortOrder())
            .active(dto.active())
            .build();
    QualityTier saved = qualityTierRepository.saveAndFlush(tier);
    auditService.record(
        AuditEventType.QUALITY_TIER_CREATED,
        saved.getId(),
        saved.getCode(),
        null,
        AuditDetails.of("code", saved.getCode())
            .with("minQuality", saved.getMinQuality())
            .with("active", saved.isActive()));
    log.info("Quality tier created: {}", saved);
    return qualityTierMapper.toDto(saved);
  }

  /**
   * Changes a tier. The floor of a referenced tier and the active flag of the base tier are fixed.
   *
   * @param id the tier
   * @param dto the new state, with the version read
   * @return the persisted tier
   * @throws BadRequestException when the version is missing or a fixed field would change
   * @throws BusinessConflictException when the floor of a referenced tier would change
   * @throws DuplicateEntityException when the code or the floor is already taken
   */
  @NotNull
  @Transactional
  public QualityTierDto update(@NotNull UUID id, @NotNull QualityTierWriteDto dto) {
    if (dto.version() == null) {
      throw new BadRequestException("version is required");
    }
    QualityTier tier = require(id);
    OptimisticLock.checkRequired(tier.getVersion(), dto.version(), QualityTier.class, id);
    assertUnique(dto, id);
    assertBaseStaysActive(dto);
    if (tier.getMinQuality() != dto.minQuality()) {
      if (tier.isBaseTier()) {
        throw new BusinessConflictException("The floor of the base tier stays 0");
      }
      if (qualityTierRepository.countReferences(id) > 0) {
        throw new BusinessConflictException(
            "Quality tier " + tier.getCode() + " is in use; its floor cannot change");
      }
    }
    final boolean deactivated = tier.isActive() && !dto.active();
    tier.setCode(dto.code());
    tier.setMinQuality(dto.minQuality());
    tier.setLabelDe(dto.labelDe().trim());
    tier.setLabelEn(dto.labelEn().trim());
    tier.setSortOrder(dto.sortOrder());
    tier.setActive(dto.active());
    QualityTier saved = qualityTierRepository.saveAndFlush(tier);
    auditService.record(
        deactivated ? AuditEventType.QUALITY_TIER_DEACTIVATED : AuditEventType.QUALITY_TIER_UPDATED,
        saved.getId(),
        saved.getCode(),
        null,
        AuditDetails.of("code", saved.getCode())
            .with("minQuality", saved.getMinQuality())
            .with("active", saved.isActive()));
    log.info("Quality tier updated: {}", saved);
    return qualityTierMapper.toDto(saved);
  }

  /**
   * Deletes a tier nothing references.
   *
   * @param id the tier
   * @throws EntityInUseException when a requirement or a claim references the tier
   * @throws BusinessConflictException when the tier is the base tier
   */
  @Transactional
  public void delete(@NotNull UUID id) {
    QualityTier tier = require(id);
    if (tier.isBaseTier()) {
      throw new BusinessConflictException("The base tier cannot be deleted");
    }
    if (qualityTierRepository.countReferences(id) > 0) {
      throw new EntityInUseException(
          "Quality tier " + tier.getCode() + " is in use; deactivate it instead");
    }
    String code = tier.getCode();
    int minQuality = tier.getMinQuality();
    qualityTierRepository.delete(tier);
    auditService.record(
        AuditEventType.QUALITY_TIER_DELETED,
        id,
        code,
        null,
        AuditDetails.of("code", code).with("minQuality", minQuality));
    log.info("Quality tier deleted: id={} code={}", id, code);
  }

  /**
   * Loads a tier or answers 404.
   *
   * @param id the tier
   * @return the managed tier
   */
  @NotNull
  private QualityTier require(@NotNull UUID id) {
    return Entities.require(
        qualityTierRepository.findById(id), () -> "Quality tier not found: " + id);
  }

  /**
   * Refuses a code or floor another tier already holds.
   *
   * @param dto the incoming state
   * @param selfId the tier being updated, or {@code null} on create
   * @throws DuplicateEntityException when the code or the floor is taken by another tier
   */
  private void assertUnique(@NotNull QualityTierWriteDto dto, @Nullable UUID selfId) {
    qualityTierRepository
        .findByCode(dto.code())
        .filter(other -> !other.getId().equals(selfId))
        .ifPresent(
            other -> {
              throw new DuplicateEntityException("Quality tier code already used: " + dto.code());
            });
    qualityTierRepository
        .findByMinQuality(dto.minQuality())
        .filter(other -> !other.getId().equals(selfId))
        .ifPresent(
            other -> {
              throw new DuplicateEntityException(
                  "Quality tier floor already used: " + dto.minQuality());
            });
  }

  /**
   * Refuses an inactive tier with floor 0.
   *
   * @param dto the incoming state
   * @throws BadRequestException when the base tier would be inactive
   */
  private static void assertBaseStaysActive(@NotNull QualityTierWriteDto dto) {
    if (dto.minQuality() == Quality.MIN && !dto.active()) {
      throw new BadRequestException("The base tier with floor 0 stays active");
    }
  }
}
