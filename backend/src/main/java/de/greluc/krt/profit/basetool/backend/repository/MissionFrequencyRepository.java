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

import de.greluc.krt.profit.basetool.backend.model.MissionFrequency;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for Mission Frequency. */
public interface MissionFrequencyRepository extends JpaRepository<MissionFrequency, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code MissionId}. */
  List<MissionFrequency> findByMissionId(UUID missionId);

  /**
   * Atomically upserts a <em>typed</em> mission frequency in a single race-free statement: inserts
   * the {@code (mission_id, frequency_type_id)} row, or updates its value in place when it already
   * exists, resolving a concurrent first-time-set on the same channel through the {@code
   * UNIQUE(mission_id, frequency_type_id)} constraint instead of the old find-then-insert TOCTOU
   * that 409'd the loser with an unresolvable phantom conflict (#1148). Last-writer-wins by design
   * (the typed upsert carries no client version); the {@code @Version} column is bumped manually on
   * update so a later JPA load stays consistent, and {@code updated_at} is refreshed. {@code
   * clearAutomatically} because this bypasses the persistence context — the caller re-fetches the
   * mission so its {@code frequencies} collection reflects the upserted row.
   *
   * @param missionId the owning mission.
   * @param frequencyTypeId the typed channel to set.
   * @param value the new frequency value (0–999.99).
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO mission_frequency"
              + " (id, mission_id, frequency_type_id, frequency_value, version, created_at,"
              + " updated_at)"
              + " VALUES (gen_random_uuid(), :missionId, :frequencyTypeId, :value, 0, now(), now())"
              + " ON CONFLICT (mission_id, frequency_type_id) DO UPDATE"
              + " SET frequency_value = EXCLUDED.frequency_value,"
              + " version = mission_frequency.version + 1, updated_at = now()",
      nativeQuery = true)
  void upsertTypedFrequency(
      @Param("missionId") UUID missionId,
      @Param("frequencyTypeId") UUID frequencyTypeId,
      @Param("value") BigDecimal value);
}
