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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The blueprint module's access policy: the gate of the org-unit blueprint availability overview,
 * evaluated on the scope kernel's leadership predicate (plan §5.4, ADR-0236).
 *
 * <p>Invoked from SpEL as {@code @blueprintAccessPolicy}. Read-only transactional.
 */
@Service(BlueprintAccessPolicy.BEAN_NAME)
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlueprintAccessPolicy {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "blueprintAccessPolicy";

  private final OwnerScopeService ownerScopeService;

  /**
   * Checks whether the caller may open the org-unit blueprint availability overview: admins,
   * officers and holders of an oversight seat.
   *
   * @return {@code true} iff the caller may open the overview
   */
  public boolean canAccessBlueprintOverview() {
    return ownerScopeService.canAccessBlueprintOverview();
  }
}
