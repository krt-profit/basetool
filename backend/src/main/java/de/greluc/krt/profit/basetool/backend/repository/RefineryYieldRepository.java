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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.RefineryYield;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Refinery Yield. */
@Repository
public interface RefineryYieldRepository extends JpaRepository<RefineryYield, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code TerminalIdAndMaterialId}. */
  Optional<RefineryYield> findByTerminalIdAndMaterialId(UUID terminalId, UUID materialId);

  /**
   * Returns every yield row whose terminal sits at the given city or space station, matched by the
   * terminal's {@code cityName} / {@code spaceStationName}.
   *
   * <p>A city-only match also requires {@code spaceStationName IS NULL}, so a station within a city
   * is not taken for a city-level refinery. Either parameter may be {@code null}; both {@code null}
   * returns an empty list.
   *
   * @param cityName name of the city to match on {@code Terminal.cityName}, or {@code null}
   * @param spaceStationName name of the space station to match on {@code
   *     Terminal.spaceStationName}, or {@code null}
   * @return matching yields, never {@code null}
   */
  @Query(
      """
      SELECT y FROM RefineryYield y
      WHERE (:cityName IS NOT NULL
              AND y.terminal.cityName = :cityName
              AND y.terminal.spaceStationName IS NULL)
         OR (:spaceStationName IS NOT NULL
              AND y.terminal.spaceStationName = :spaceStationName)
      """)
  List<RefineryYield> findAllForLocation(
      @Param("cityName") String cityName, @Param("spaceStationName") String spaceStationName);

  /**
   * The (material, terminal) key and id of every refinery yield row. One query for the whole
   * matrix; the UEX sync resolves "does this pair have a row yet" in memory instead of with a
   * lookup per row (BE-PERF-09).
   *
   * @return one (parent id, terminal id, row id) row per matrix row
   */
  @Query(
      "SELECT e.material.id AS parentId, e.terminal.id AS terminalId, e.id AS id FROM RefineryYield"
          + " e")
  List<PairKeyRef> findYieldKeyRefs();
}
