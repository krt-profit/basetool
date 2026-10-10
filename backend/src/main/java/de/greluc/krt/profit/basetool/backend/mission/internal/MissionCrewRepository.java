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

import de.greluc.krt.profit.basetool.backend.model.MissionCrew;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for Mission Crew. */
public interface MissionCrewRepository extends JpaRepository<MissionCrew, UUID> {

  /**
   * Derived Spring-Data check - returns {@code true} iff at least one row matches {@code
   * JobTypesId}.
   */
  boolean existsByJobTypesId(UUID jobTypeId);

  /** Derived Spring-Data query - returns entities matching {@code JobTypesId}. */
  List<MissionCrew> findByJobTypesId(UUID jobTypeId);
}
