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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.UexKeyRef;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository for Planet. */
public interface PlanetRepository extends JpaRepository<Planet, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code IdPlanet}. */
  Optional<Planet> findByIdPlanet(Integer id);

  /** Derived Spring-Data query - returns entities matching {@code Name}. */
  Optional<Planet> findByName(String name);

  /**
   * Reads every Planet's (idPlanet, local id) pair in one query, for the lookup map the commodity
   * location sync resolves UEX ids against (REQ-DATA-031).
   *
   * @return one row per Planet that carries a UEX id
   */
  @Query("SELECT e.idPlanet AS uexId, e.id AS id FROM Planet e WHERE e.idPlanet IS NOT NULL")
  List<UexKeyRef> findUexRefs();

  /**
   * Reads the identities and name fallbacks needed by one UEX synchronization chunk.
   *
   * @param ids incoming UEX identifiers
   * @param names incoming display names
   * @return matching rows in stable local-id order
   */
  List<Planet> findByIdPlanetInOrNameInOrderByIdAsc(
      Collection<Integer> ids, Collection<String> names);
}
