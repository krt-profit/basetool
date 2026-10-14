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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tells a member whether purchasing is on and which parts of it they may use (REQ-PUR-001). */
@Service
@RequiredArgsConstructor
public class PurchasingStateService {

  private final PurchasingSettingsService settingsService;
  private final PurchasingAccessPolicy accessPolicy;

  /**
   * Reads the switch and the caller's capabilities; a switched-off surface reveals nothing beyond
   * the switch.
   *
   * @return the state for the caller
   */
  @NotNull
  @Transactional(readOnly = true)
  public PurchasingStateDto state() {
    if (!settingsService.isEnabled()) {
      return new PurchasingStateDto(false, false, false, false, false, false, false);
    }
    return new PurchasingStateDto(
        true,
        accessPolicy.isActiveDelegate(),
        accessPolicy.isGoverningLeadership(),
        accessPolicy.isBankStaff(),
        !accessPolicy.managedUnitIds().isEmpty(),
        accessPolicy.canReadReport(),
        settingsService.current().isPaused());
  }
}
