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

package de.greluc.krt.profit.basetool.frontend.patchchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/**
 * Mirror of the backend's kinds of what a patch-change effect counts (REQ-PATCH-005); each kind is
 * one tile of the preview, labelled by {@code admin.patchChange.count.<KIND>}.
 *
 * <p>Must carry every backend constant under the same name, or deserialising a response that
 * contains it fails.
 */
@DtoMirror
public enum PatchCount {
  /** The stock rows the wipe removes (REQ-PATCH-012). */
  STOCK_ROWS_WIPED,

  /** The in-game ships the wipe removes (REQ-PATCH-015). */
  IN_GAME_SHIPS_WIPED,

  /** The members who hold a stock row the wipe removes (REQ-PATCH-016). */
  STOCK_MEMBERS_AFFECTED,

  /** The running refinery orders the refinery effect cancels (REQ-PATCH-006). */
  REFINERY_ORDERS_CANCELED,

  /** The job orders with an earmark on a wiped row (REQ-PATCH-013). */
  JOB_ORDERS_AFFECTED,

  /** The Materialbörse offers that end with their stock (REQ-PATCH-013). */
  MARKET_OFFERS_ENDED,

  /** The donation pledges withdrawn with their stock (REQ-PATCH-013). */
  DONATIONS_WITHDRAWN
}
