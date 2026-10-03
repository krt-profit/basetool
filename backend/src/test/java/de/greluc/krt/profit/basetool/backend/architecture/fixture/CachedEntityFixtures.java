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

package de.greluc.krt.profit.basetool.backend.architecture.fixture;

import de.greluc.krt.profit.basetool.backend.config.CacheConfig;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.repository.CityRepository;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;

/**
 * A planted query/command split over a cached catalogue entity, the shape REQ-DATA-022 forbids: the
 * command service loads the entity through the other service's cache and edits the shared instance.
 * Neither type is a Spring bean unless a test declares it.
 */
public final class CachedEntityFixtures {

  /** Not instantiable. */
  private CachedEntityFixtures() {}

  /** The query half: a cache holding the entity itself. */
  public static class PlantedCityQueries {

    private final CityRepository cityRepository;

    /**
     * Creates the fixture.
     *
     * @param cityRepository the repository behind the cache
     */
    public PlantedCityQueries(CityRepository cityRepository) {
      this.cityRepository = cityRepository;
    }

    /**
     * Loads a city through the cache.
     *
     * @param id the city
     * @return the cached instance
     */
    @Cacheable(cacheNames = CacheConfig.CITIES_CACHE, key = "'g19-planted-' + #id")
    @Transactional(readOnly = true)
    public City find(UUID id) {
      return Entities.require(cityRepository.findById(id), "City not found");
    }
  }

  /** The command half: edits the instance the query half cached. */
  public static class PlantedCityCommands {

    private final PlantedCityQueries queries;

    private final CityRepository cityRepository;

    /**
     * Creates the fixture.
     *
     * @param queries the cached query half
     * @param cityRepository the repository the command saves through
     */
    public PlantedCityCommands(PlantedCityQueries queries, CityRepository cityRepository) {
      this.queries = queries;
      this.cityRepository = cityRepository;
    }

    /**
     * Flips the loading-dock flag on the cached instance and saves it.
     *
     * @param id the city
     * @return the saved city
     */
    @Transactional
    public City toggleLoadingDock(UUID id) {
      City city = queries.find(id);
      city.setHasLoadingDock(!Boolean.TRUE.equals(city.getHasLoadingDock()));
      return cityRepository.save(city);
    }
  }
}
