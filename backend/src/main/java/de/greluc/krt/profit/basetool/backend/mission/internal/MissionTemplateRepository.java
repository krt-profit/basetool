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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Persistence for the mission template aggregate and scoped list projection. */
interface MissionTemplateRepository extends JpaRepository<MissionTemplate, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM MissionTemplate t WHERE t.id = :id")
  Optional<MissionTemplate> lock(UUID id);

  Optional<MissionTemplate> findByIdAndOwningOrgUnitId(UUID id, UUID owner);

  @Lock(LockModeType.PESSIMISTIC_READ)
  @Query("SELECT t FROM MissionTemplate t WHERE t.id = :id AND t.owningOrgUnit.id = :owner")
  Optional<MissionTemplate> lockForApplication(UUID id, UUID owner);

  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.mission.internal.MissionTemplateSummaryDto(
        t.id, t.owningOrgUnit.id, t.name, t.version)
      FROM MissionTemplate t
      WHERE t.owningOrgUnit.id = :owner
      ORDER BY t.name, t.id
      """)
  Page<MissionTemplateSummaryDto> list(UUID owner, Pageable pageable);
}
