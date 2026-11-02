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

package de.greluc.krt.profit.basetool.backend.scope.api;

import de.greluc.krt.profit.basetool.backend.identity.api.SquadronVisibility;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@link SquadronVisibility} adapter of the scope kernel, plus the Logistician-in-owner guard.
 * Callers use {@link RequestScopeResolver} for the request-scoped context and {@link
 * AccessGateService} for the {@code can*} gates directly.
 *
 * <p>Read-only transactional.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerScopeService implements SquadronVisibility {

  private final RequestScopeResolver requestScopeResolver;
  private final AccessGateService accessGateService;

  /**
   * Delegates to {@link RequestScopeResolver#currentUserListScopeSquadronIds()}: the SQUADRON-scope
   * set for the admin user-list / search / promotion queries, or {@code null} for the unfiltered
   * all-scope.
   *
   * @return the squadron id set the user-list queries filter on, or {@code null} for the unfiltered
   *     admin/leadership all-scope.
   */
  @Nullable
  @Override
  public Set<UUID> currentUserListScopeSquadronIds() {
    return requestScopeResolver.currentUserListScopeSquadronIds();
  }

  /**
   * Delegates to {@link AccessGateService#canSeeSquadron(UUID)}: whether the caller may read data
   * owned by {@code squadronId}.
   *
   * @param squadronId the org-unit id whose data the caller wants to read; never {@code null}.
   * @return {@code true} iff the caller may see the given org unit's data.
   */
  @Override
  public boolean canSeeSquadron(@NotNull UUID squadronId) {
    return accessGateService.canSeeSquadron(squadronId);
  }

  /**
   * Refuses the caller unless they act as Logistician in the unit that owns the target.
   *
   * @param orgUnit the target's owning org unit, or {@code null} when it has none (admin only)
   * @throws AccessDeniedException when the caller is no Logistician of {@code orgUnit}
   */
  public void requireLogisticianInOwner(@Nullable OrgUnit orgUnit) {
    if (!accessGateService.canActAsLogisticianInOwner(OrgUnit.idOf(orgUnit))) {
      throw new AccessDeniedException("Access denied: not a Logistician of the target's org unit");
    }
  }
}
