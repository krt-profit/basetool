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
 * The caller's effective org-unit scope, resolved by {@link
 * OwnerScopeService#currentScopePredicate()}.
 *
 * <ul>
 *   <li>Admin without selection ({@link #adminAllScope()}): no filter applies.
 *   <li>Pinned caller ({@link #activeOrgUnitId()} non-null): only that org unit.
 *   <li>Unpinned non-admin: the union of {@link #memberOrgUnitIds()} (Staffel and SK memberships).
 * </ul>
 *
 * <p>Repository queries apply it as:
 *
 * <pre>{@code
 * (:isAdminAllScope = true)
 *   OR (:scopeOrgUnitId IS NOT NULL AND x.owningOrgUnit.id = :scopeOrgUnitId)
 *   OR (:scopeOrgUnitId IS NULL AND x.owningOrgUnit.id IN :memberOrgUnitIds)
 * }</pre>
 *
 * <p>Mission additionally allows {@code OR x.isInternal = false} (REQ-ORG-009).
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
   * In-memory equivalent of the JPQL scope clause, for per-row detail and write gates: permits an
   * org unit iff a row it owns would appear in this caller's scoped list view.
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
