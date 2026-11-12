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

/** HQL visibility of a mission and its parent chain (REQ-DATA-010, REQ-ORG-003). */
public final class MissionScope {

  private MissionScope() {}

  /** The row read rule for alias {@code m}. */
  private static final String ROW_READABLE =
      """
      (
        :isAdminAllScope = true
        OR coalesce(m.isInternal, false) = false
        OR (m.owningOrgUnit IS NOT NULL AND (
          (:activeOrgUnitId IS NOT NULL AND m.owningOrgUnit.id = :activeOrgUnitId)
          OR (:activeOrgUnitId IS NULL AND m.owningOrgUnit.id IN :memberOrgUnitIds)
        ))
        OR (m.owningOrgUnit IS NULL AND :viewerIsMemberOrAbove = true)
       )
      """;

  /** Recursive prefix collecting unreadable ancestors and their descendants. */
  static final String ANCESTOR_VISIBILITY =
      """
      WITH unreadable_mission AS (
        SELECT m.id AS id FROM Mission m
        WHERE EXISTS (SELECT 1 FROM Mission child WHERE child.parent.id = m.id)
          AND NOT
      """
          + ROW_READABLE
          + "  UNION\n"
          + "  SELECT child.id AS id FROM Mission child\n"
          + "  JOIN unreadable_mission ancestor ON child.parent.id = ancestor.id\n"
          + ")\n";

  /** Read rule for alias {@code m} in a query prefixed with {@link #ANCESTOR_VISIBILITY}. */
  public static final String PREDICATE =
      ROW_READABLE + "AND m.id NOT IN (SELECT hidden.id FROM unreadable_mission hidden)\n";
}
