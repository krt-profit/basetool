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

import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Job Type. */
@Repository
public interface JobTypeRepository extends LookupTableRepository<JobType, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code Archetype}. */
  List<JobType> findByArchetype(JobTypeArchetype archetype);

  /** Derived Spring-Data query - returns entities matching {@code Archetype}. */
  Page<JobType> findByArchetype(JobTypeArchetype archetype, Pageable pageable);

  /** Derived Spring-Data query - returns entities matching {@code ArchetypeAndActiveTrue}. */
  List<JobType> findByArchetypeAndActiveTrue(JobTypeArchetype archetype);

  /** Derived Spring-Data query - returns entities matching {@code ArchetypeAndActiveTrue}. */
  Page<JobType> findByArchetypeAndActiveTrue(JobTypeArchetype archetype, Pageable pageable);

  /** Derived Spring-Data query - returns entities matching {@code ActiveTrue}. */
  List<JobType> findByActiveTrue();

  /** Derived Spring-Data query - returns entities matching {@code ActiveTrue}. */
  Page<JobType> findByActiveTrue(Pageable pageable);

  /**
   * Derived Spring-Data check - returns {@code true} iff at least one row matches {@code ParentId}.
   */
  boolean existsByParentId(UUID parentId);

  /** Derived Spring-Data query - returns entities matching {@code ParentId}. */
  List<JobType> findByParentId(UUID parentId);

  /**
   * Returns the job type(s) currently designated as the "Einsatzleiter" (mission lead). A partial
   * unique index (V200) keeps this to at most one row, but the query returns a list so the
   * re-designation path can defensively clear any stragglers. JPQL is used (not a derived query) to
   * reference the {@code isMissionLead} field unambiguously.
   *
   * @return the designated mission-lead job types (normally zero or one)
   */
  @Query("SELECT j FROM JobType j WHERE j.isMissionLead = true")
  List<JobType> findAllMissionLead();
}
