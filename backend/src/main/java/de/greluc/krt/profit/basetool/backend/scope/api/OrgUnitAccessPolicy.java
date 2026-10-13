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

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The org-unit gates of the scope kernel as a named policy (plan section 5.4): whether the caller
 * may read or write the data of an org unit, evaluated against the same scope as the scoped lists.
 *
 * <p>Read-only transactional, like the gate it delegates to.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrgUnitAccessPolicy {

  private final AccessGateService accessGateService;

  /**
   * Checks whether the caller may see data owned by the org unit (Staffel or Spezialkommando).
   *
   * @param orgUnitId the org-unit id whose data the caller wants to read; never {@code null}
   * @return {@code true} iff the caller may see the org unit's data
   */
  public boolean canSeeOrgUnit(@NotNull UUID orgUnitId) {
    return accessGateService.canSeeSquadron(orgUnitId);
  }

  /**
   * Checks whether the caller may write data owned by the org unit; currently the same rule as
   * {@link #canSeeOrgUnit(UUID)}.
   *
   * @param orgUnitId the org-unit id whose data the caller wants to write; never {@code null}
   * @return {@code true} iff the caller may write the org unit's data
   */
  public boolean canEditOrgUnit(@NotNull UUID orgUnitId) {
    return accessGateService.canEditSquadron(orgUnitId);
  }
}
