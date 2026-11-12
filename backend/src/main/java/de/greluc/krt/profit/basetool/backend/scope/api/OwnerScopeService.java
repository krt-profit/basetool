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

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Logistician-in-owner guard of the scope kernel. Callers use {@link RequestScopeResolver} for
 * the request-scoped context and {@link AccessGateService} for the {@code can*} gates directly.
 *
 * <p>Read-only transactional.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerScopeService {

  private final AccessGateService accessGateService;

  /**
   * Refuses the caller unless they act as Logistician in the unit that owns the target.
   *
   * @param orgUnit the target's owning org unit, or {@code null} when it has none (then only an
   *     admin without a pin passes)
   * @throws AccessDeniedException when the caller is no Logistician of {@code orgUnit}
   */
  public void requireLogisticianInOwner(@Nullable OrgUnit orgUnit) {
    if (!accessGateService.canActAsLogisticianInOwner(OrgUnit.idOf(orgUnit))) {
      throw new AccessDeniedException("Access denied: not a Logistician of the target's org unit");
    }
  }
}
