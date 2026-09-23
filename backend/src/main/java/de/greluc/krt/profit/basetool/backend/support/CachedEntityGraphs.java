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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Initialises the lazy to-one associations of an entity <em>before</em> it enters a Caffeine
 * {@code @Cacheable} cache (BE-PERF-11).
 *
 * <p>A cached entity outlives the persistence context that loaded it and is handed to every later
 * caller, on any thread. While these associations were EAGER the whole graph was loaded before the
 * cache stored it. Made LAZY, an association nobody happened to touch would be cached as an
 * uninitialised proxy bound to a session that has since closed, and the first later reader to touch
 * it — the next request's mapper, or a write path that attaches the cached entity to a new row —
 * would fail with {@code LazyInitializationException}, or, worse, race the loading session from
 * another thread. So every cached read of these four types passes its result through here, inside
 * the service's own read-only transaction, and the cache only ever holds complete graphs.
 *
 * <p>Initialisation goes through Hibernate's batch fetching ({@code default_batch_fetch_size}), so
 * a page of materials costs one statement per association level, not one per row.
 */
public final class CachedEntityGraphs {

  private CachedEntityGraphs() {
    // Static helpers — not instantiable.
  }

  /**
   * Initialises a material's category and its whole {@code refinedMaterial} chain, each link with
   * its own category — exactly the graph {@code MaterialMapper.toDto} walks recursively.
   *
   * @param material the material about to be cached; {@code null} passes through
   * @return {@code material}, for chaining
   */
  @Contract("null -> null; !null -> !null")
  public static @Nullable Material material(@Nullable Material material) {
    Set<UUID> visited = new HashSet<>();
    for (Material current = material;
        current != null && visited.add(current.getId());
        current = current.getRefinedMaterial()) {
      Hibernate.initialize(current);
      Hibernate.initialize(current.getCategory());
    }
    return material;
  }

  /**
   * Initialises every material of a cached list or page.
   *
   * @param materials the materials about to be cached
   * @param <T> the iterable type, returned as given
   * @return {@code materials}, for chaining
   */
  public static <T extends Iterable<Material>> @NotNull T materials(@NotNull T materials) {
    materials.forEach(CachedEntityGraphs::material);
    return materials;
  }

  /**
   * Initialises a location's city and space station.
   *
   * @param location the location about to be cached; {@code null} passes through
   * @return {@code location}, for chaining
   */
  @Contract("null -> null; !null -> !null")
  public static @Nullable Location location(@Nullable Location location) {
    if (location != null) {
      Hibernate.initialize(location.getCity());
      Hibernate.initialize(location.getSpaceStation());
    }
    return location;
  }

  /**
   * Initialises every location of a cached list or page.
   *
   * @param locations the locations about to be cached
   * @param <T> the iterable type, returned as given
   * @return {@code locations}, for chaining
   */
  public static <T extends Iterable<Location>> @NotNull T locations(@NotNull T locations) {
    locations.forEach(CachedEntityGraphs::location);
    return locations;
  }

  /**
   * Initialises a ship type's manufacturer.
   *
   * @param shipType the ship type about to be cached; {@code null} passes through
   * @return {@code shipType}, for chaining
   */
  @Contract("null -> null; !null -> !null")
  public static @Nullable ShipType shipType(@Nullable ShipType shipType) {
    if (shipType != null) {
      Hibernate.initialize(shipType.getManufacturer());
    }
    return shipType;
  }

  /**
   * Initialises every ship type of a cached list or page.
   *
   * @param shipTypes the ship types about to be cached
   * @param <T> the iterable type, returned as given
   * @return {@code shipTypes}, for chaining
   */
  public static <T extends Iterable<ShipType>> @NotNull T shipTypes(@NotNull T shipTypes) {
    shipTypes.forEach(CachedEntityGraphs::shipType);
    return shipTypes;
  }

  /**
   * Initialises a job type's whole {@code parent} chain. The mappers read only {@code parent.id},
   * which a proxy answers without loading, but a cached graph is complete or it is a trap for the
   * next reader.
   *
   * @param jobType the job type about to be cached; {@code null} passes through
   * @return {@code jobType}, for chaining
   */
  @Contract("null -> null; !null -> !null")
  public static @Nullable JobType jobType(@Nullable JobType jobType) {
    Set<UUID> visited = new HashSet<>();
    for (JobType current = jobType;
        current != null && visited.add(current.getId());
        current = current.getParent()) {
      Hibernate.initialize(current);
    }
    return jobType;
  }

  /**
   * Initialises every job type of a cached list or page.
   *
   * @param jobTypes the job types about to be cached
   * @param <T> the iterable type, returned as given
   * @return {@code jobTypes}, for chaining
   */
  public static <T extends Iterable<JobType>> @NotNull T jobTypes(@NotNull T jobTypes) {
    jobTypes.forEach(CachedEntityGraphs::jobType);
    return jobTypes;
  }
}
