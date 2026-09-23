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
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.model.StarSystem;
import de.greluc.krt.profit.basetool.backend.repository.StarSystemRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cached CRUD service for the {@code star_system} table.
 *
 * <p>UEX owns the bulk of this table; this service adds the admin-only create/update/delete API for
 * systems UEX doesn't know about yet (e.g. just-announced systems before they appear in the UEX
 * feed). Case-insensitive uniqueness on name is enforced explicitly via existence checks — a DB
 * unique index alone would surface as a generic 500 instead of a 409 with a localized message.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StarSystemService {

  private final StarSystemRepository starSystemRepository;

  /**
   * Returns cached page of star systems.
   *
   * @param pageable page request
   * @return cached page of star systems
   */
  @Cacheable(cacheNames = CacheConfig.STAR_SYSTEMS_CACHE)
  public Page<StarSystem> getAllStarSystems(@NotNull Pageable pageable) {
    return starSystemRepository.findAll(pageable);
  }

  /**
   * Returns the star system.
   *
   * @param id star system primary key
   * @return the star system
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no match
   */
  @Cacheable(cacheNames = CacheConfig.STAR_SYSTEMS_CACHE)
  public StarSystem getStarSystem(@NotNull UUID id) {
    return Entities.require(starSystemRepository.findById(id), "StarSystem not found");
  }

  /**
   * Persists a new star system. Case-insensitive duplicate name throws {@link
   * DuplicateEntityException} → 409 before the DB unique-constraint would.
   *
   * @param starSystem transient entity
   * @return the persisted star system
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.STAR_SYSTEMS_CACHE, allEntries = true)
  public StarSystem createStarSystem(@NotNull StarSystem starSystem) {
    if (starSystemRepository.existsByNameIgnoreCase(starSystem.getName())) {
      throw new DuplicateEntityException(
          "A Star System with the name '" + starSystem.getName() + "' already exists.");
    }
    return starSystemRepository.save(starSystem);
  }

  /**
   * Updates name and description of an existing star system. Other fields (UEX flags, jurisdiction,
   * faction) come from UEX and are not mutable here.
   *
   * @param id star system primary key
   * @param starSystemDetails transient entity carrying the new values
   * @return the persisted star system
   * @throws DuplicateEntityException when the new name collides with a different system
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.STAR_SYSTEMS_CACHE, allEntries = true)
  public StarSystem updateStarSystem(@NotNull UUID id, @NotNull StarSystem starSystemDetails) {
    if (starSystemRepository.existsByNameIgnoreCaseAndIdNot(starSystemDetails.getName(), id)) {
      throw new DuplicateEntityException(
          "A Star System with the name '" + starSystemDetails.getName() + "' already exists.");
    }
    StarSystem starSystem = getStarSystem(id);

    starSystem.setName(starSystemDetails.getName());
    starSystem.setDescription(starSystemDetails.getDescription());

    return starSystemRepository.save(starSystem);
  }

  /**
   * Deletes a star system. Rejected when any location still references the system.
   *
   * @param id star system primary key
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.STAR_SYSTEMS_CACHE, allEntries = true)
  public void deleteStarSystem(@NotNull UUID id) {
    StarSystem starSystem = getStarSystem(id);
    starSystemRepository.delete(starSystem);
  }
}
