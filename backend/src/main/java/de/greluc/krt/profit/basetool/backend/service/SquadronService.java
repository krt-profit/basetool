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

import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.SquadronDto;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached CRUD service for the {@code squadron} reference table.
 *
 * <p>Squadrons are the organizational units a user can be assigned to (each user belongs to one
 * squadron at a time; mission participants may name a different squadron for that mission's roster
 * line). Same soft-delete + case-insensitive uniqueness pattern as {@link JobTypeService}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SquadronService {

  private final SquadronRepository squadronRepository;

  /**
   * Unpaged squadron list for dropdowns.
   *
   * @param includeInactive when true, include soft-deleted entries
   * @return cached list
   */
  @Cacheable(cacheNames = CacheConfig.SQUADRONS_CACHE)
  public List<Squadron> getAllSquadrons(boolean includeInactive) {
    return includeInactive
        ? squadronRepository.findAll()
        : squadronRepository.findAllByActiveTrue();
  }

  /**
   * Paged variant for the admin list.
   *
   * @param pageable page request
   * @param includeInactive when true, include soft-deleted entries
   * @return cached page
   */
  @Cacheable(cacheNames = CacheConfig.SQUADRONS_CACHE)
  public Page<Squadron> getAllSquadrons(@NotNull Pageable pageable, boolean includeInactive) {
    return includeInactive
        ? squadronRepository.findAll(pageable)
        : squadronRepository.findAllByActiveTrue(pageable);
  }

  /**
   * Returns the squadron.
   *
   * @param id squadron primary key
   * @return the squadron
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  public Squadron getSquadronById(@NotNull UUID id) {
    return squadronRepository
        .findById(id)
        .orElseThrow(() -> new NotFoundException("Squadron not found"));
  }

  /**
   * Persists a new squadron. Case-insensitive duplicate name throws {@link
   * DuplicateEntityException}.
   *
   * @param squadron transient entity
   * @return the persisted squadron
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.SQUADRONS_CACHE, allEntries = true)
  public Squadron createSquadron(@NotNull Squadron squadron) {
    if (squadronRepository.existsByNameIgnoreCase(squadron.getName())) {
      throw new DuplicateEntityException(
          "A Squadron with the name '" + squadron.getName() + "' already exists.");
    }
    return squadronRepository.save(squadron);
  }

  /**
   * Updates an existing squadron with optimistic-lock and duplicate-name checks.
   *
   * @param id squadron primary key
   * @param squadronDto update payload
   * @return the persisted squadron
   * @throws DuplicateEntityException when the new name collides with a different row
   * @throws ObjectOptimisticLockingFailureException when the supplied version is stale
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.SQUADRONS_CACHE, allEntries = true)
  public Squadron updateSquadron(@NotNull UUID id, @NotNull SquadronDto squadronDto) {
    if (squadronRepository.existsByNameIgnoreCaseAndIdNot(squadronDto.name(), id)) {
      throw new DuplicateEntityException(
          "A Squadron with the name '" + squadronDto.name() + "' already exists.");
    }
    Squadron squadron = getSquadronById(id);

    OptimisticLock.check(squadron.getVersion(), squadronDto.version(), Squadron.class, id);

    squadron.setName(squadronDto.name());
    squadron.setShorthand(squadronDto.shorthand());
    squadron.setDescription(squadronDto.description());
    return squadronRepository.save(squadron);
  }

  /**
   * Soft-deletes a squadron by flipping {@code active=false}.
   *
   * @param id squadron primary key
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.SQUADRONS_CACHE, allEntries = true)
  public void deleteSquadron(@NotNull UUID id) {
    if (!squadronRepository.existsById(id)) {
      throw new NotFoundException("Squadron not found");
    }
    Squadron squadron = getSquadronById(id);
    squadron.setActive(false);
    squadronRepository.save(squadron);
  }

  /**
   * Reverses a soft-delete.
   *
   * @param id squadron primary key
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.SQUADRONS_CACHE, allEntries = true)
  public void activateSquadron(@NotNull UUID id) {
    Squadron squadron = getSquadronById(id);
    squadron.setActive(true);
    squadronRepository.save(squadron);
  }

  /**
   * Toggles the per-squadron promotion-feature flag. Kept as a dedicated mutator separate from
   * {@link #updateSquadron(UUID, SquadronDto)} so the flag cannot be flipped as an accidental
   * side-effect of editing the squadron's name/shorthand/description, and so the audit trail in the
   * access log can clearly attribute "X disabled promotion for squadron Y" to the admin who pressed
   * the toggle. Flipping the flag never touches the promotion data — categories, ranks, and
   * evaluations stay in the DB and become visible again as soon as the flag goes back to {@code
   * true}.
   *
   * @param id squadron primary key
   * @param enabled new value of {@code is_promotion_enabled}; {@code true} re-exposes a previously
   *     hidden squadron, {@code false} hides the promotion menu for the squadron's non-admin
   *     members.
   * @return the persisted squadron
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no matching row.
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.SQUADRONS_CACHE, allEntries = true)
  public Squadron setPromotionEnabled(@NotNull UUID id, boolean enabled) {
    Squadron squadron = getSquadronById(id);
    squadron.setPromotionEnabled(enabled);
    return squadronRepository.save(squadron);
  }

  /**
   * Toggles the per-squadron profit-eligibility flag deciding whether the squadron may be picked as
   * the responsible (processing) org unit of a Job Order. Kept as a dedicated mutator separate from
   * {@link #updateSquadron(UUID, SquadronDto)} — for the same reasons as {@link
   * #setPromotionEnabled(UUID, boolean)} — so the flag cannot be flipped as an accidental
   * side-effect of a name/shorthand/description edit and the access log can attribute the change to
   * the admin who pressed the toggle. Flipping the flag never touches any Job Order; it only
   * changes whether the squadron appears in the responsible picker from now on.
   *
   * @param id squadron primary key
   * @param eligible new value of {@code is_profit_eligible}; {@code true} makes the squadron
   *     selectable as a Job-Order processor, {@code false} removes it from the responsible picker.
   * @return the persisted squadron
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no matching row.
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.SQUADRONS_CACHE, allEntries = true)
  public Squadron setProfitEligible(@NotNull UUID id, boolean eligible) {
    Squadron squadron = getSquadronById(id);
    squadron.setProfitEligible(eligible);
    return squadronRepository.save(squadron);
  }
}
