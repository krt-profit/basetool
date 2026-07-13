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

import de.greluc.krt.profit.basetool.backend.model.OrgChartPosition;
import de.greluc.krt.profit.basetool.backend.model.OrgChartPositionType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link OrgChartPosition}. The read methods eagerly fetch the {@code
 * user} (and, for unit-scoped rows, the {@code orgUnit}) via an entity graph so {@code
 * OrgChartService} can assemble the whole chart without an N+1 storm; the count / exists methods
 * back the cardinality and one-user-per-scope guards the service enforces before a write.
 */
@Repository
public interface OrgChartPositionRepository extends JpaRepository<OrgChartPosition, UUID> {

  /**
   * Returns every area-leadership position (Bereichsleitung — {@code org_unit_id IS NULL}), ordered
   * for display, with the holding user fetched. Used by the chart-assembly read path.
   *
   * @return the area-leadership positions, ordered by sort index then creation time; never {@code
   *     null}, possibly empty.
   */
  @EntityGraph(attributePaths = {"user", "parent"})
  List<OrgChartPosition> findAllByOrgUnitIsNullOrderBySortIndexAscCreatedAtAsc();

  /**
   * Returns every position belonging to one of the given OrgUnits (the profit-eligible Staffeln +
   * SKs), ordered for display, with the user and OrgUnit fetched. Callers must pass a non-empty
   * collection — {@code OrgChartService} skips this query entirely when no profit-eligible unit
   * exists, avoiding a dialect-fragile empty {@code IN ()}.
   *
   * @param orgUnitIds the OrgUnit ids to load positions for; must be non-empty.
   * @return the matching positions, ordered by sort index then creation time; never {@code null}.
   */
  @EntityGraph(attributePaths = {"user", "orgUnit", "parent"})
  List<OrgChartPosition> findAllByOrgUnitIdInOrderBySortIndexAscCreatedAtAsc(
      Collection<UUID> orgUnitIds);

  /**
   * Counts how many positions of the given type already exist in one OrgUnit. Backs the per-Staffel
   * limits (≤4 Kommandoleiter, ≤4 Ensign) and the per-SK limit (≤2 SK-Leiter).
   *
   * @param orgUnitId the OrgUnit to count within; never {@code null}.
   * @param positionType the functional rank to count; never {@code null}.
   * @return the current number of positions of that type in that OrgUnit.
   */
  long countByOrgUnitIdAndPositionType(UUID orgUnitId, OrgChartPositionType positionType);

  /**
   * Whether an area-leadership position of the given type already exists ({@code org_unit_id IS
   * NULL}). Backs the "exactly one Bereichsleiter" guard before the {@code
   * uq_org_chart_one_area_lead} index would reject the insert.
   *
   * @param positionType the area-leadership rank to check (typically {@code AREA_LEAD}); never
   *     {@code null}.
   * @return {@code true} iff such an area-leadership position already exists.
   */
  boolean existsByOrgUnitIsNullAndPositionType(OrgChartPositionType positionType);

  /**
   * Whether a child position of the given type already hangs off the given parent. Backs the "at
   * most one Stv. Kommandoleiter per Kommandoleiter" guard, mirroring the {@code
   * uq_org_chart_one_deputy_per_command} index.
   *
   * @param parentId the parent position id; never {@code null}.
   * @param positionType the child rank to check (typically {@code DEPUTY_COMMAND_LEAD}); never
   *     {@code null}.
   * @return {@code true} iff such a child already exists under that parent.
   */
  boolean existsByParentIdAndPositionType(UUID parentId, OrgChartPositionType positionType);

  /**
   * Whether the user already holds any position in the given OrgUnit. Backs the "a user appears at
   * most once per Staffel/SK" guard, surfacing a friendly error before the {@code
   * uq_org_chart_user_per_unit} index would reject the insert.
   *
   * @param orgUnitId the OrgUnit to check; never {@code null}.
   * @param userId the candidate user; never {@code null}.
   * @return {@code true} iff the user already has a position in that OrgUnit.
   */
  boolean existsByOrgUnitIdAndUserId(UUID orgUnitId, UUID userId);

  /**
   * Whether the user already holds an area-leadership position ({@code org_unit_id IS NULL}). Backs
   * the "a user appears at most once in the Bereichsleitung" guard, mirroring the {@code
   * uq_org_chart_user_in_area} index.
   *
   * @param userId the candidate user; never {@code null}.
   * @return {@code true} iff the user already has an area-leadership position.
   */
  boolean existsByOrgUnitIsNullAndUserId(UUID userId);

  /**
   * Returns the (at most one, per the {@code uq_org_chart_user_per_unit} index) positions the given
   * user holds in the given OrgUnit. Backs the chart-mirror reconcile (epic #800, REQ-ROLE-006):
   * the appointment service clears the user's current seat before (re)creating the one matching the
   * freshly-assigned rank.
   *
   * @param orgUnitId the OrgUnit to look within; never {@code null}.
   * @param userId the holding user; never {@code null}.
   * @return the user's positions in that OrgUnit; never {@code null}, possibly empty.
   */
  List<OrgChartPosition> findByOrgUnitIdAndUserId(UUID orgUnitId, UUID userId);

  /**
   * Returns the Kommando node ({@code COMMAND_LEAD}) that mirrors the given Kommandogruppe, if any.
   * Unique by {@code uq_org_chart_one_command_per_group}. Backs the squadron-rank mirror (epic
   * #800, REQ-ROLE-006): a Kommandoleiter fills this node's holder, a stellv. Kommandoleiter /
   * Ensign hangs off it via {@code parent_id}.
   *
   * @param kommandoGroupId the Kommandogruppe whose mirror node to load; never {@code null}.
   * @return the mirroring {@code COMMAND_LEAD} position, or empty when none exists yet.
   */
  Optional<OrgChartPosition> findByKommandoGroupId(UUID kommandoGroupId);

  /**
   * Returns the first position of the given type in the given OrgUnit, ordered by sort index then
   * creation time. Backs the singleton-seat reassign in the chart mirror (epic #800, REQ-ROLE-006)
   * for {@code SQUADRON_LEAD} / {@code BEREICHSLEITER}, where re-appointing a new holder reuses the
   * single existing chart row rather than creating a second one that the partial unique index would
   * reject.
   *
   * @param orgUnitId the OrgUnit to look within; never {@code null}.
   * @param positionType the singleton rank to find; never {@code null}.
   * @return the existing singleton position, or empty when none exists yet.
   */
  Optional<OrgChartPosition> findFirstByOrgUnitIdAndPositionTypeOrderBySortIndexAscCreatedAtAsc(
      UUID orgUnitId, OrgChartPositionType positionType);

  /**
   * Returns every position of the given type in the given OrgUnit. Backs the free-text-placeholder
   * reuse in the chart mirror (REQ-ROLE-006): when an account is appointed to a multi-holder post,
   * a matching free-text placeholder of the same type is converted instead of leaving a duplicate,
   * so the mirror scans the type's seats for a name match.
   *
   * @param orgUnitId the OrgUnit to look within; never {@code null}.
   * @param positionType the rank to enumerate; never {@code null}.
   * @return the positions of that type in the unit; never {@code null}, possibly empty.
   */
  List<OrgChartPosition> findByOrgUnitIdAndPositionType(
      UUID orgUnitId, OrgChartPositionType positionType);

  /**
   * Returns the child position of the given type hanging off the given parent, if any. Backs the
   * stellv.-Kommandoleiter reassign in the chart mirror (epic #800, REQ-ROLE-006): a Kommando has
   * at most one {@code DEPUTY_COMMAND_LEAD}, so re-appointing reuses that single child row.
   *
   * @param parentId the parent Kommando position id; never {@code null}.
   * @param positionType the child rank to find (typically {@code DEPUTY_COMMAND_LEAD}); never
   *     {@code null}.
   * @return the existing child position, or empty when none exists yet.
   */
  Optional<OrgChartPosition> findByParentIdAndPositionType(
      UUID parentId, OrgChartPositionType positionType);
}
