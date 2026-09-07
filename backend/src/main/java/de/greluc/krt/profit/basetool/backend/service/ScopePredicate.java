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

package de.greluc.krt.profit.basetool.backend.service;

import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Plan §3.5 effective-scope vector resolved by {@link OwnerScopeService#currentScopePredicate()}.
 * Encodes the three orthogonal cases that every staffel-scoped read query has to honour:
 *
 * <ul>
 *   <li><b>Admin without active selection</b> ({@link #adminAllScope()} = {@code true}): the caller
 *       sees data across every OrgUnit, no filter applies. Both {@link #activeOrgUnitId()} and
 *       {@link #memberOrgUnitIds()} are empty here — admins are not constrained until they pin a
 *       specific selection via the switcher.
 *   <li><b>Admin or non-admin pinned to a specific OrgUnit</b> ({@link #activeOrgUnitId()} non-
 *       null): the caller sees data owned by that single OrgUnit. The admin-pinned case relies on
 *       the {@code X-Active-Org-Unit-Id} request header; the non-admin pinned case (R5.e) relies on
 *       the same header propagated by the frontend switcher widened to non-admins.
 *   <li><b>Non-admin without pinning</b> ({@link #memberOrgUnitIds()} non-empty, {@link
 *       #activeOrgUnitId()} null, {@link #adminAllScope()} = {@code false}): the caller sees the
 *       union of every OrgUnit they belong to (Staffel + every SK membership). This is the case
 *       where today's "single Staffel id" view silently dropped SK data — the new predicate fixes
 *       that by passing the full membership set into the {@code IN} clause.
 * </ul>
 *
 * <p>There used to be a fourth case here: an anonymous caller, all fields empty, seeing nothing
 * through the scoped clause and reaching data only through Mission's organisation-wide escape. That
 * case is gone with the anonymous surface (ADR-0159), and {@code
 * RequestScopeResolver#currentScopePredicate()} now throws rather than building it — an all-empty
 * predicate is indistinguishable from a legitimate one and would answer a question nobody was
 * entitled to ask.
 *
 * <p>Used in repository queries by the standard JPQL fragment
 *
 * <pre>{@code
 * (:isAdminAllScope = true)
 *   OR (:scopeOrgUnitId IS NOT NULL AND x.owningOrgUnit.id = :scopeOrgUnitId)
 *   OR (:scopeOrgUnitId IS NULL AND x.owningOrgUnit.id IN :memberOrgUnitIds)
 * }</pre>
 *
 * <p>Mission (cross-staffel aggregate) adds {@code OR x.isInternal = false} as the
 * organisation-wide escape clause — {@code isInternal = false} means "every member of the
 * organisation", never "everyone" (REQ-ORG-009, D8). The empty-collection case for {@link
 * #memberOrgUnitIds()} returns no rows from the {@code IN} clause (Hibernate 6 handles {@code IN
 * ()} as a constant {@code false}).
 *
 * @param adminAllScope {@code true} iff the caller is an admin with no active selection — the
 *     filter clauses are short-circuited to "all rows visible".
 * @param activeOrgUnitId the single OrgUnit id the caller pinned via the switcher; {@code null}
 *     when no pinning is active.
 * @param memberOrgUnitIds the union of OrgUnit ids the caller is a member of (Staffel + SK
 *     memberships); empty for admins and for a member who belongs to no unit.
 */
public record ScopePredicate(
    boolean adminAllScope, @Nullable UUID activeOrgUnitId, @NotNull Set<UUID> memberOrgUnitIds) {

  /**
   * Convenience accessor for the JPQL guard against an empty {@link #memberOrgUnitIds()}. Some
   * repository predicates short-circuit on this flag to avoid issuing an {@code IN ()} clause even
   * on Hibernate versions that may not optimise it cleanly.
   *
   * @return {@code true} iff {@link #memberOrgUnitIds()} is empty.
   */
  public boolean memberOrgUnitIdsEmpty() {
    return memberOrgUnitIds.isEmpty();
  }

  /**
   * In-memory mirror of the JPQL scope clause documented above, for the per-row {@code canSee*} /
   * {@code canEdit*} detail and write gates. A single org-unit id is permitted iff a row owned by
   * it would appear in this caller's scoped <em>list</em> view: admin-all-scope sees everything, a
   * pinned caller sees only the pinned id, and an unpinned non-admin sees any org unit in their
   * membership union (Staffel + every Spezialkommando). Keeping this on the record guarantees the
   * detail/edit checks and the list queries can never diverge.
   *
   * @param orgUnitId the org-unit id (Staffel or Spezialkommando) to test; never {@code null}.
   * @return {@code true} iff a row owned by {@code orgUnitId} is in scope for this caller.
   */
  public boolean permits(@NotNull UUID orgUnitId) {
    return adminAllScope
        || (activeOrgUnitId != null && activeOrgUnitId.equals(orgUnitId))
        || memberOrgUnitIds.contains(orgUnitId);
  }
}
