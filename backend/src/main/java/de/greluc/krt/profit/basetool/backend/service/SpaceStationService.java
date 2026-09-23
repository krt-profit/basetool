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

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.SpaceStation;
import de.greluc.krt.profit.basetool.backend.repository.SpaceStationRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read service plus admin-override mutators for the space-station catalogue. The records themselves
 * are owned by {@link UexUniverseSyncService}; this service only exposes the read API and the
 * admin-only {@code hasLoadingDock} pin used by the UEX-overrides admin page.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpaceStationService {

  private final SpaceStationRepository spaceStationRepository;

  /**
   * Returns the paged space-station catalogue.
   *
   * @param pageable page request
   * @return one page of space stations, sorted by the pageable's sort
   */
  public Page<SpaceStation> getAllSpaceStations(Pageable pageable) {
    return spaceStationRepository.findAll(pageable);
  }

  /**
   * Returns a single space station by primary key.
   *
   * @param id space station primary key
   * @return the managed space-station entity
   * @throws NotFoundException when no space station matches the id
   */
  public SpaceStation getSpaceStation(UUID id) {
    return Entities.require(spaceStationRepository.findById(id), "Space station not found");
  }

  /**
   * Pins {@code hasLoadingDock} to the supplied value and marks the row as admin-overridden so the
   * next UEX sweep leaves the value column untouched.
   *
   * @param id space station primary key
   * @param value desired {@code hasLoadingDock} value
   * @return the persisted space station
   */
  @Transactional
  public SpaceStation setLoadingDockOverride(UUID id, boolean value) {
    SpaceStation station = getSpaceStation(id);
    station.setHasLoadingDock(value);
    station.setHasLoadingDockOverridden(true);
    return spaceStationRepository.save(station);
  }

  /**
   * Releases the admin pin on {@code hasLoadingDock}. The value column stays at its last value
   * until the next UEX sweep overwrites it from the upstream feed.
   *
   * @param id space station primary key
   * @return the persisted space station
   */
  @Transactional
  public SpaceStation clearLoadingDockOverride(UUID id) {
    SpaceStation station = getSpaceStation(id);
    station.setHasLoadingDockOverridden(false);
    return spaceStationRepository.save(station);
  }
}
