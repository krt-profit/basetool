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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write gate of the minimum stock levels: a Logistician of the level's unit, the lead included,
 * or an admin (REQ-INV-068). Reads follow the Lager scope in {@link StockLevelRepository}.
 *
 * <p>Invoked from SpEL as {@code @stockLevelAccessPolicy}. Unknown ids are refused.
 */
@Service(StockLevelAccessPolicy.BEAN_NAME)
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockLevelAccessPolicy {

  /** The bean name the {@code @PreAuthorize} expressions reference. */
  public static final String BEAN_NAME = "stockLevelAccessPolicy";

  private final OwnerScopeService ownerScopeService;
  private final StockLevelRepository stockLevelRepository;

  /**
   * Checks whether the caller may maintain the minimum stock levels of a unit's pool.
   *
   * @param orgUnitId the unit; never {@code null}
   * @return {@code true} iff the caller may create, change and delete the unit's levels
   */
  public boolean canMaintainStockLevelsOf(@NotNull UUID orgUnitId) {
    return ownerScopeService.hasRoleInOrgUnit(orgUnitId, Roles.LOGISTICIAN);
  }

  /**
   * Checks whether the caller may change or delete minimum stock level {@code levelId}.
   *
   * @param levelId the level; never {@code null}
   * @return {@code true} iff the level exists and the caller may maintain its unit's levels
   */
  public boolean canMaintainStockLevel(@NotNull UUID levelId) {
    return stockLevelRepository
        .findById(levelId)
        .map(level -> canMaintainStockLevelsOf(level.getOrgUnit().getId()))
        .orElse(false);
  }
}
