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
 * Shared JPQL fragments for the org-unit scope-predicate triple (S5, #911): {@code :isAdminAllScope
 * = true OR (:activeOrgUnitId IS NOT NULL AND &lt;alias&gt;.owningOrgUnit.id = :activeOrgUnitId) OR
 * (:activeOrgUnitId IS NULL AND &lt;alias&gt;.owningOrgUnit.id IN :memberOrgUnitIds)} — see {@code
 * de.greluc.krt.profit.basetool.backend.service.ScopePredicate} for the three-parameter shape these
 * fragments bind against, and REQ-ORG-003 in {@code docs/specs/org-unit-tenancy.md} for the scope
 * kinds themselves.
 *
 * <p><b>Why constants and not a runtime builder.</b> Every consumer is a repository method's
 * {@code @Query} annotation value, which the Java Language Specification requires to be a
 * compile-time constant expression (JLS 15.28/4.12.4) — an annotation cannot invoke a method. A
 * {@code public static final String} initialized from a text block, referenced from another class
 * as {@code ScopeSpecifications.SHIP_SCOPE_TRIPLE}, is itself a compile-time constant (a "qualified
 * name of a constant variable" per JLS 15.28 — text blocks without embedded expressions are String
 * literals like any other) and folds byte-for-byte into the referencing {@code @Query} value at
 * compile time — the same technique S3 (#909) used to reference {@code Roles.ADMIN} from
 * {@code @PreAuthorize}. This keeps the six call sites that repeat the triple verbatim in one place
 * without paying a runtime cost or requiring {@code JpaSpecificationExecutor} (unused anywhere in
 * this codebase — every scoped query is a hand-written {@code @Query}, not a {@code
 * Specification}). Query methods whose body needs more than the triple splice this constant after a
 * leading text block ({@code """head""" + ScopeSpecifications.X + " tail"}, S7 Option A, #913); any
 * further content after the constant stays a plain string literal, not a second text block — Google
 * Java Format always collapses a text block onto the same line as a preceding {@code +}, which then
 * fails this project's {@code TextBlockGoogleStyleFormatting} Checkstyle rule (opening quotes must
 * start their own line), so a text block can only be the <em>first</em> operand of a concatenation
 * here, never a later one. The whole expression is still one compile-time constant per the same JLS
 * rule regardless of which operands are text blocks vs. quoted literals.
 *
 * <p><b>Why {@code public} and not package-private.</b> Although every consumer lives in this same
 * {@code repository} package, the type is declared {@code public} on purpose: its constants are
 * referenced only inside {@code @Query} annotation values, so constant folding (see above) inlines
 * each value and leaves no reference to this type in the compiled output. A package-private holder
 * would therefore be reported as an unused type by reference analysis (e.g. CodeQL {@code
 * java/unused-reference-type}) even though it backs every scoped query — declaring it {@code
 * public} keeps that analysis correct without changing any behaviour.
 *
 * <p><b>The alias is baked into each constant, not parameterized.</b> Each entity's repository
 * picks its own JPQL alias ({@code o} for Operation, {@code m} for Mission, {@code s} for Ship,
 * {@code r} for RefineryOrder, {@code i} for InventoryItem, {@code o} for JobOrder), and a
 * compile-time constant cannot be built with a runtime alias substitution — so one constant exists
 * per aggregate rather than one generic template. This is why the base is a handful of named
 * constants, not a single shared string.
 *
 * <p><b>Escape tails stay baked into the owning aggregate's constant, not composed on top.</b>
 * Three of the six aggregates layer an extra {@code OR} clause onto the plain three-branch triple:
 * {@link #OPERATION_SCOPE_PREDICATE} adds the ownerless-leadership escape and the mission-
 * participant escape (REQ-ORG-003), {@link #MISSION_SCOPE_PREDICATE} adds the cross-staffel public
 * escape ({@code isInternal = false}) and its own ownerless-leadership escape, and {@link
 * #JOB_ORDER_SCOPE_PREDICATE} adds the SK-public-queue escape. These are still each one
 * compile-time constant (a single text-block literal), not a runtime "prefix + tail" join — Java
 * constant folding cannot compose two separately-referenced constants from two different
 * {@code @Query} annotations at different call sites into one value; each full predicate, tail
 * included, is declared once here and reused verbatim everywhere that aggregate's scope applies.
 * {@link #SHIP_SCOPE_TRIPLE}, {@link #REFINERY_ORDER_SCOPE_TRIPLE} and {@link
 * #INVENTORY_ITEM_SCOPE_TRIPLE} carry no escape tail — those three aggregates are strict-staffel
 * with no cross-squadron visibility beyond the triple (REQ-ORG-003).
 */
public final class ScopeSpecifications {

  /** Non-instantiable JPQL-fragment constant holder. */
  private ScopeSpecifications() {}

  /**
   * Operation's scope predicate (alias {@code o}, field {@code owningOrgUnit}): the plain triple
   * plus the two read-only escapes documented under REQ-ORG-003 — an ownerless leadership operation
   * surfaces to organisation members-or-above, and any authenticated user who participated in one
   * of the operation's linked missions sees it regardless of owning OrgUnit. Reused verbatim by
   * {@code OperationRepository.findAllScoped}, {@code #findAllReferenceScoped} and {@code
   * #searchOperations} — mirroring them was previously a manual copy-paste (the pre-S5 Javadoc on
   * {@code findAllReferenceScoped} admitted as much).
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
   * Mission's scope predicate (alias {@code m}, field {@code owningOrgUnit}): the plain triple plus
   * the cross-staffel public escape ({@code isInternal = false} missions stay visible outside the
   * owning scope) and the ownerless-leadership-mission escape, per REQ-ORG-003. Reused verbatim by
   * {@code MissionRepository.findAllActiveReference} and both {@code searchMissions} overloads
   * (List and Page).
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
   * Ship's scope predicate (alias {@code s}, field {@code owningOrgUnit}): the plain triple with no
   * escape tail — Ship (Hangar) is strict-staffel per REQ-ORG-003. Reused verbatim by {@code
   * ShipRepository.resetAllFittedScoped}, {@code #findAllScoped}, {@code #countShipsByType} (both
   * its value and count query) and {@code #findByShipTypeInScoped}.
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
   * RefineryOrder's scope predicate (alias {@code r}, field {@code owningOrgUnit}): the plain
   * triple with no escape tail — Refinery is strict-staffel per REQ-ORG-003. Reused verbatim by
   * {@code RefineryOrderRepository.findByMissionIdScoped}, {@code #findByOwnerIdScoped}, {@code
   * #findAllScoped} and {@code #findByStatusInScoped}.
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
   * InventoryItem's scope predicate (alias {@code i}, field {@code owningOrgUnit}): the plain
   * triple with no escape tail — the direct Lager-View is strict-staffel per REQ-ORG-003. Reused
   * verbatim by {@code InventoryItemRepository.findByMaterialAndPersonalFalseScoped}, {@code
   * #findByGameItemAndPersonalFalseScoped}, {@code #findGlobalByFilters}, {@code
   * #findGlobalItemsByFilters}, {@code #findGlobalStacks}, {@code #findGlobalItemStacks}, {@code
   * #findGlobalStackEntries}, {@code #findGlobalItemStackEntries}, {@code #getAggregatedInventory},
   * {@code #getAggregatedItemInventory} and {@code #deleteAllNonPersonal}.
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
   * JobOrder's scope predicate (alias {@code o}, field {@code responsibleOrgUnit} — NOT {@code
   * owningOrgUnit}): the plain triple against the <em>responsible</em> (processing) OrgUnit, plus
   * the SK-public-queue escape ({@code TYPE(responsibleOrgUnit) = SpecialCommand} orders are
   * visible to every squadron), per REQ-ORG-003. Currently reused by the single scoped list query
   * {@code JobOrderRepository.findScopedJobOrders}; centralised here (rather than left inline)
   * because a future second JobOrder-scoped query must reuse this exact predicate — re-deriving it
   * by hand risks silently dropping the SK-public escape and reintroducing a #343-style visibility
   * bug.
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
