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

/**
 * Shared JPQL fragments for the org-unit scope-predicate triple (REQ-ORG-003): admin all-scope,
 * else the pinned {@code activeOrgUnitId}, else the {@code memberOrgUnitIds} (see {@code
 * de.greluc.krt.profit.basetool.backend.service.ScopePredicate}).
 *
 * <p>The fragments are compile-time constants so they can be spliced into {@code @Query} values;
 * one exists per aggregate because each bakes in its entity's alias. A query that needs more than
 * the triple starts with a text block, appends the constant and continues only with plain string
 * literals. The class is {@code public} because constant folding leaves no compiled reference to
 * it.
 *
 * <p>{@link #OPERATION_SCOPE_PREDICATE}, {@link #MISSION_SCOPE_PREDICATE} and {@link
 * #JOB_ORDER_SCOPE_PREDICATE} include their aggregate's visibility escapes; {@link
 * #SHIP_SCOPE_TRIPLE}, {@link #REFINERY_ORDER_SCOPE_TRIPLE} and {@link
 * #INVENTORY_ITEM_SCOPE_TRIPLE} are the plain strict-staffel triple.
 */
public final class ScopeSpecifications {

  /** Non-instantiable JPQL-fragment constant holder. */
  private ScopeSpecifications() {}

  /**
   * Operation's scope predicate (alias {@code o}): the triple plus the read escapes of REQ-ORG-003,
   * so an ownerless leadership operation is visible to organisation members and above, and a
   * participant of one of its missions sees it regardless of owning org unit.
   */
  static final String OPERATION_SCOPE_PREDICATE =
      """
      (
        :isAdminAllScope = true
        OR (:activeOrgUnitId IS NOT NULL AND o.owningOrgUnit.id = :activeOrgUnitId)
        OR (:activeOrgUnitId IS NULL AND o.owningOrgUnit.id IN :memberOrgUnitIds)
        OR (o.owningOrgUnit IS NULL AND :viewerIsMemberOrAbove = true)
        OR (:viewerUserId IS NOT NULL AND EXISTS (SELECT p.id FROM MissionParticipant p
         WHERE p.mission.operation = o AND p.user.id = :viewerUserId))
       )
      """;

  /**
   * Mission's scope predicate (alias {@code m}): the triple plus the public escape ({@code
   * isInternal = false}) and the ownerless-leadership escape (REQ-ORG-003).
   */
  static final String MISSION_SCOPE_PREDICATE =
      """
      (
        :isAdminAllScope = true
        OR (:activeOrgUnitId IS NOT NULL AND m.owningOrgUnit.id = :activeOrgUnitId)
        OR (:activeOrgUnitId IS NULL AND m.owningOrgUnit.id IN :memberOrgUnitIds)
        OR m.isInternal = false
        OR (m.owningOrgUnit IS NULL AND :viewerIsMemberOrAbove = true)
       )
      """;

  /**
   * Ship's scope predicate (alias {@code s}): the plain triple; the hangar is strict-staffel
   * (REQ-ORG-003).
   */
  static final String SHIP_SCOPE_TRIPLE =
      """
      (
        :isAdminAllScope = true
        OR (:activeOrgUnitId IS NOT NULL AND s.owningOrgUnit.id = :activeOrgUnitId)
        OR (:activeOrgUnitId IS NULL AND s.owningOrgUnit.id IN :memberOrgUnitIds)
       )
      """;

  /**
   * RefineryOrder's scope predicate (alias {@code r}): the plain triple; refinery is strict-staffel
   * (REQ-ORG-003).
   */
  static final String REFINERY_ORDER_SCOPE_TRIPLE =
      """
      (
        :isAdminAllScope = true
        OR (:activeOrgUnitId IS NOT NULL AND r.owningOrgUnit.id = :activeOrgUnitId)
        OR (:activeOrgUnitId IS NULL AND r.owningOrgUnit.id IN :memberOrgUnitIds)
       )
      """;

  /**
   * InventoryItem's scope predicate (alias {@code i}): the plain triple; the direct Lager view is
   * strict-staffel (REQ-ORG-003).
   */
  static final String INVENTORY_ITEM_SCOPE_TRIPLE =
      """
      (
        :isAdminAllScope = true
        OR (:activeOrgUnitId IS NOT NULL AND i.owningOrgUnit.id = :activeOrgUnitId)
        OR (:activeOrgUnitId IS NULL AND i.owningOrgUnit.id IN :memberOrgUnitIds)
       )
      """;

  /**
   * JobOrder's scope predicate (alias {@code o}): the triple against {@code responsibleOrgUnit},
   * not {@code owningOrgUnit}, plus the SK-public-queue escape that shows orders of a {@code
   * SpecialCommand} to every squadron (REQ-ORG-003).
   */
  static final String JOB_ORDER_SCOPE_PREDICATE =
      """
      (
        :isAdminAllScope = true
        OR TYPE(o.responsibleOrgUnit) = SpecialCommand
        OR (:activeOrgUnitId IS NOT NULL AND o.responsibleOrgUnit.id = :activeOrgUnitId)
        OR (:activeOrgUnitId IS NULL AND o.responsibleOrgUnit.id IN :memberOrgUnitIds)
       )
      """;
}
