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

package de.greluc.krt.profit.basetool.backend.supply.api;

import de.greluc.krt.profit.basetool.backend.platform.api.ObserverSpi;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tells a higher module how a need changed, inside the transaction that changed it (plan §5.3). The
 * purchasing module implements it to notify the delegates and to cancel the deals of a withdrawn
 * need (REQ-PUR-020); every method joins the caller's transaction ({@code MANDATORY}).
 */
@ObserverSpi
public interface NeedLifecycleObserver {

  /**
   * A need was raised as purchasable, or a need was set purchasable.
   *
   * @param need the need after the change
   * @param actorSub the member who changed it, or {@code null}
   */
  void onReleasedForPurchase(@NotNull NeedRef need, @Nullable UUID actorSub);

  /**
   * A need was withdrawn.
   *
   * @param needId the need
   */
  void onWithdrawn(@NotNull UUID needId);

  /**
   * A need became fulfilled.
   *
   * @param needId the need
   */
  void onFulfilled(@NotNull UUID needId);
}
