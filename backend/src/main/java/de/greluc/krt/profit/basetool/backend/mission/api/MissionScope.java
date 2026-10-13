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

package de.greluc.krt.profit.basetool.backend.mission.api;

/**
 * The JPQL scope fragment of the module's scoped aggregate (REQ-DATA-010), spliced into the
 * {@code @Query} values of the repositories that list the aggregate.
 *
 * <p>The fragment lives beside the aggregate's repository and is the list-side half of the rule
 * that {@code MissionAccessPolicy} enforces row by row: both widen together (plan section 5.4). It
 * is a compile-time constant, which an annotation value needs, so the class is {@code public} and
 * has no behaviour. A query that needs more than the fragment starts with a text block, appends the
 * constant and continues only with plain string literals.
 */
public final class MissionScope {

  /** Non-instantiable constant holder. */
  private MissionScope() {}

  /**
   * Mission's scope predicate (alias {@code m}): the org-unit triple plus the public escape ({@code
   * isInternal = false}) and the ownerless-leadership escape (REQ-ORG-003).
   */
  public static final String PREDICATE =
      """
      (
        :isAdminAllScope = true
        OR (:activeOrgUnitId IS NOT NULL AND m.owningOrgUnit.id = :activeOrgUnitId)
        OR (:activeOrgUnitId IS NULL AND m.owningOrgUnit.id IN :memberOrgUnitIds)
        OR m.isInternal = false
        OR (m.owningOrgUnit IS NULL AND :viewerIsMemberOrAbove = true)
       )
      """;
}
