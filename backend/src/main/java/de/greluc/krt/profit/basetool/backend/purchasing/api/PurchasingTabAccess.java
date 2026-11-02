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

package de.greluc.krt.profit.basetool.backend.purchasing.api;

import org.jetbrains.annotations.NotNull;

/**
 * Tells the caller's layout read which purchasing tabs the caller may open (REQ-UI-026), by the
 * same rules the purchasing reads behind those tabs enforce.
 */
public interface PurchasingTabAccess {

  /**
   * Decides the purchasing tabs for the calling member; all are off while the surface is switched
   * off.
   *
   * @return the tabs the caller may open
   */
  @NotNull
  PurchasingTabGrants current();

  /**
   * The purchasing tabs the caller may open.
   *
   * @param partners whether „Partner" is offered: an active trade delegate or the governing
   *     leadership
   * @param delegates whether „Handelsbeauftragte" is offered: the governing leadership
   * @param report whether „Bericht" is offered: a delegate, the governing leadership, bank staff or
   *     the lead or a Logistician of a unit
   */
  record PurchasingTabGrants(boolean partners, boolean delegates, boolean report) {

    /** No tab: the answer while the purchasing surface is off. */
    public static final PurchasingTabGrants NONE = new PurchasingTabGrants(false, false, false);
  }
}
