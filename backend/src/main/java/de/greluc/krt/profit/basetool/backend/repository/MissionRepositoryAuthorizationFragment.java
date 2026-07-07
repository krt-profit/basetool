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

import de.greluc.krt.profit.basetool.backend.model.Mission;
import java.util.Optional;
import java.util.UUID;

/**
 * Custom-repository fragment giving {@link MissionRepository} a collection-free, {@code
 * EntityManager.find}-based mission lookup for the authorization gates (#1139).
 *
 * <p>The overridden {@link MissionRepository#findById(UUID)} eagerly graphs the {@code
 * participants} roster, and any derived / JPQL query alternative would auto-flush the persistence
 * context. The authorization gates ({@code AccessGateService.canSeeMission} / {@code
 * canEditMission}, {@code MissionSecurityService.canManageMission} / {@code canManageManagers} /
 * {@code canChangeOwner}) read only the mission's {@code owningOrgUnit} / {@code parent} chain or
 * {@code owner} / {@code managers} — never the roster — so loading the aggregate there was pure
 * per-request waste, doubling the heaviest query on the hottest endpoint. This fragment resolves
 * the gate load through {@code em.find}, which (a) fetches the plain entity with <em>no</em>
 * collection graph, so the gate lazy-loads only the one or two associations it actually reads, (b)
 * is first-level-cache aware, and (c) — unlike a JPQL query — never auto-flushes, matching the
 * production {@code readOnly} gate transaction even when the gate joins an outer read-write
 * transaction (a {@code @Transactional} test).
 */
public interface MissionRepositoryAuthorizationFragment {

  /**
   * Loads a mission by id for an authorization decision via {@code EntityManager.find} — no
   * collection graph, first-level-cache aware, and never auto-flushing (#1139). The caller
   * lazy-loads the single associations it needs ({@code owningOrgUnit} / {@code parent} for the
   * scope gates, {@code owner} / {@code managers} for the owner-manager gates) within its own
   * transaction; the roster is never touched, so no {@code participants} / {@code assignedUnits}
   * query runs.
   *
   * @param id the mission id
   * @return the mission, or empty when none exists
   */
  Optional<Mission> findByIdForAuthorization(UUID id);
}
