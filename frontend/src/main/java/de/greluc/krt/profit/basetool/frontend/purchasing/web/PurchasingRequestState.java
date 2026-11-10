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

package de.greluc.krt.profit.basetool.frontend.purchasing.web;

import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingStateDto;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/** Shares one immutable purchasing capability snapshot within the current HTTP request. */
@Component
@RequestScope
@RequiredArgsConstructor
public class PurchasingRequestState {

  private final PurchasingBackendClient purchasingClient;
  private boolean loaded;
  private PurchasingStateDto state;

  /**
   * Reads the capability snapshot once, retaining a successful empty response as well.
   *
   * @return the caller's capabilities, or null when the backend sent no body
   */
  public @Nullable PurchasingStateDto read() {
    if (!loaded) {
      state = purchasingClient.state();
      loaded = true;
    }
    return state;
  }

  /** Discards this request's snapshot after a purchasing configuration write. */
  public void forget() {
    loaded = false;
    state = null;
  }
}
