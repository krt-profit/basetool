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

import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingTabAccess;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides the purchasing tabs of the caller (REQ-UI-026) by the gates of the reads behind them: the
 * partner list, the delegate list and the report.
 */
@Service
@RequiredArgsConstructor
public class PurchasingTabAccessService implements PurchasingTabAccess {

  private final PurchasingSettingsService settingsService;
  private final PurchasingAccessPolicy accessPolicy;

  /**
   * Decides the tabs: „Partner" by {@link PurchasingAccessPolicy#canMaintainPartners()},
   * „Handelsbeauftragte" by {@link PurchasingAccessPolicy#canAppointDelegates()}, „Bericht" by
   * {@link PurchasingAccessPolicy#canReadReport()}; none while the surface is off.
   *
   * @return the tabs the caller may open
   */
  @Override
  @Transactional(readOnly = true)
  public @NotNull PurchasingTabGrants current() {
    if (!settingsService.isEnabled()) {
      return PurchasingTabGrants.NONE;
    }
    boolean partners = accessPolicy.canMaintainPartners();
    // canReadReport() admits every partner maintainer; the short circuit skips its repeated reads.
    return new PurchasingTabGrants(
        partners, accessPolicy.canAppointDelegates(), partners || accessPolicy.canReadReport());
  }
}
