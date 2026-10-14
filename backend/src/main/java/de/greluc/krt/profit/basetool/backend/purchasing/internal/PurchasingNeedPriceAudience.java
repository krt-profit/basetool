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

import de.greluc.krt.profit.basetool.backend.supply.api.NeedPriceAudience;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lets the active trade delegates read the maximum price and the purchase progress of every need
 * (REQ-SUP-006): they price their deals against it.
 */
@Component
@RequiredArgsConstructor
class PurchasingNeedPriceAudience implements NeedPriceAudience {

  private final PurchasingAccessPolicy accessPolicy;

  @Override
  @Transactional(readOnly = true)
  public boolean mayReadPurchaseFields(@NotNull UUID userId) {
    return accessPolicy.isActiveDelegate(userId);
  }
}
