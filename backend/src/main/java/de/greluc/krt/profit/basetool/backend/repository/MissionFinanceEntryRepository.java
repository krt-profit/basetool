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

import de.greluc.krt.profit.basetool.backend.model.MissionFinanceEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Mission Finance Entry. */
@Repository
public interface MissionFinanceEntryRepository extends JpaRepository<MissionFinanceEntry, UUID> {

  /** Returns every entity matching the derived {@code findAllByMissionId} criteria. */
  Page<MissionFinanceEntry> findAllByMissionId(UUID missionId, Pageable pageable);

  /** Returns every entity matching the derived {@code findAllByMissionId} criteria. */
  List<MissionFinanceEntry> findAllByMissionId(UUID missionId);

  /** Returns every entity matching the derived {@code findAllByMissionIdIn} criteria. */
  List<MissionFinanceEntry> findAllByMissionIdIn(List<UUID> missionIds);

  /**
   * Aggregates a mission's finance entries into per-type sum + count in ONE grouped query, so the
   * finance summary strip and the total-sum endpoint no longer materialize every row. Under the
   * multi-user mission-page live-update fan-out (ADR-0078) the previous {@code size=1000} load-all
   * pinned a database connection per render and, at 200 viewers, starved the pool. A SQL {@code
   * SUM} over an empty set is {@code NULL}, so the aggregate's sums may be {@code null} (no entry
   * of that type); the caller coalesces them to zero. Counts are never {@code null} (0 when none).
   *
   * @param missionId mission id
   * @return per-type sum/count aggregate; sums may be {@code null}, counts are 0 when none
   */
  @Query(
      "select new de.greluc.krt.profit.basetool.backend.repository.FinanceEntryAggregate("
          + "sum(case when e.type ="
          + " de.greluc.krt.profit.basetool.backend.model.FinanceType.INCOME then e.amount end),"
          + " count(case when e.type ="
          + " de.greluc.krt.profit.basetool.backend.model.FinanceType.INCOME then 1 end),"
          + " sum(case when e.type ="
          + " de.greluc.krt.profit.basetool.backend.model.FinanceType.EXPENSE then e.amount end),"
          + " count(case when e.type ="
          + " de.greluc.krt.profit.basetool.backend.model.FinanceType.EXPENSE then 1 end))"
          + " from MissionFinanceEntry e where e.mission.id = :missionId")
  FinanceEntryAggregate aggregateFinanceByMission(@Param("missionId") UUID missionId);

  /** Derived Spring-Data delete - removes every row matching {@code MissionIdIn}. */
  @Modifying
  @Query("DELETE FROM MissionFinanceEntry m WHERE m.mission.id IN :missionIds")
  void deleteByMissionIdIn(@Param("missionIds") List<UUID> missionIds);
}
