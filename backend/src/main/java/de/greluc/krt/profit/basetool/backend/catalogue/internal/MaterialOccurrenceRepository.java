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

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for {@link MaterialOccurrence} (REQ-DATA-031). */
public interface MaterialOccurrenceRepository extends JpaRepository<MaterialOccurrence, UUID> {

  /**
   * Loads the occurrences of the given materials, location references left lazy — the sync's
   * current state for one chunk, read in one query.
   *
   * @param materialIds the local material ids
   * @return every occurrence of those materials
   */
  @Query("SELECT o FROM MaterialOccurrence o WHERE o.material.id IN :materialIds")
  List<MaterialOccurrence> findByMaterialIds(@Param("materialIds") Collection<UUID> materialIds);

  /**
   * Loads the occurrences of one material with all five location references fetched, so the read
   * DTO is built without a further query.
   *
   * @param materialId the local material id
   * @return the material's occurrences, empty for an unknown material
   */
  @Query(
      """
      SELECT o FROM MaterialOccurrence o
      LEFT JOIN FETCH o.starSystem
      LEFT JOIN FETCH o.planet
      LEFT JOIN FETCH o.moon
      LEFT JOIN FETCH o.orbit
      LEFT JOIN FETCH o.poi
      WHERE o.material.id = :materialId
      """)
  List<MaterialOccurrence> findWithLocationsByMaterialId(@Param("materialId") UUID materialId);
}
