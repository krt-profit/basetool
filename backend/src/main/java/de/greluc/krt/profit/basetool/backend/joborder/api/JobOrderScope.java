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

package de.greluc.krt.profit.basetool.backend.joborder.api;

/** Shared JPQL readership for web order pages, counts, lookups and material representations. */
public final class JobOrderScope {

  /** Non-instantiable constant holder. */
  private JobOrderScope() {}

  /** Complete web order readership, independent of the reduced allocation and Exchange scope. */
  public static final String READ_PREDICATE =
      """
      (
        :isAdminAllScope = true
        OR (:activeOrgUnitId IS NOT NULL AND (o.responsibleOrgUnit.id = :activeOrgUnitId
          OR o.requestingOrgUnit.id = :activeOrgUnitId))
        OR (:activeOrgUnitId IS NULL AND (o.responsibleOrgUnit.id IN :memberOrgUnitIds
          OR o.requestingOrgUnit.id IN :memberOrgUnitIds))
        OR EXISTS (SELECT h.id FROM JobOrderUnitReadHistory h WHERE h.jobOrder = o
          AND ((:activeOrgUnitId IS NOT NULL AND h.orgUnit.id = :activeOrgUnitId)
            OR (:activeOrgUnitId IS NULL AND h.orgUnit.id IN :memberOrgUnitIds)))
        OR EXISTS (SELECT p.id FROM JobOrderParticipation p WHERE p.jobOrder = o
          AND ((:activeOrgUnitId IS NOT NULL AND p.claimingOrgUnit.id = :activeOrgUnitId)
            OR (:activeOrgUnitId IS NULL AND p.claimingOrgUnit.id IN :memberOrgUnitIds)))
        OR EXISTS (SELECT a.id FROM JobOrderAssignee a WHERE a.jobOrder = o
          AND a.user.id = :callerId)
      )
      """;
}
