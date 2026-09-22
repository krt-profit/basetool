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
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
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
 * Read service plus visibility toggle for the ship-type catalog. Parallels {@link
 * ManufacturerService} — the underlying records are owned by {@link UexVehicleService}; this
 * exposes the cached read surface and the admin-only visibility flip.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShipTypeService {

  private final ShipTypeRepository shipTypeRepository;

  /**
   * Returns cached page result.
   *
   * @param pageable page request
   * @param includeHidden true to include ship types marked hidden
   * @return cached page result
   */
  @Cacheable(cacheNames = CacheConfig.SHIP_TYPES_CACHE)
  public Page<ShipType> getAllShipTypes(@NotNull Pageable pageable, boolean includeHidden) {
    if (includeHidden) {
      return shipTypeRepository.findAll(pageable);
    }
    return shipTypeRepository.findByHiddenFalse(pageable);
  }

  /**
   * Returns the ship type.
   *
   * @param id ship type primary key
   * @return the ship type
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when no ship type
   *     matches
   */
  @Cacheable(cacheNames = CacheConfig.SHIP_TYPES_CACHE)
  public ShipType getShipType(@NotNull UUID id) {
    return shipTypeRepository
        .findById(id)
        .orElseThrow(() -> new NotFoundException("ShipType not found"));
  }

  /**
   * Flips the {@code hidden} flag on a ship type. Evicts the full ship-type cache so the next read
   * sees the new state immediately.
   *
   * @param id ship type primary key
   * @param hidden new flag value
   * @return the persisted ship type
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.SHIP_TYPES_CACHE, allEntries = true)
  public ShipType updateShipTypeVisibility(@NotNull UUID id, boolean hidden) {
    ShipType shipType = getShipType(id);
    shipType.setHidden(hidden);
    return shipTypeRepository.save(shipType);
  }
}
