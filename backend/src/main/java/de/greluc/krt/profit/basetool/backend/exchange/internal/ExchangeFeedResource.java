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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

/**
 * A change feed of the exchange, stored as {@code exchange_change.resource} (REQ-XCH-013).
 *
 * <p>The constant names are the column's values that {@code ck_exchange_change_resource} allows.
 * The writable resources map onto their feed through {@link ExchangeResource#feed()}.
 */
public enum ExchangeFeedResource {
  /** The member's personal blueprints, keyed by product. */
  BLUEPRINT,

  /** The member's stock, keyed by lot. */
  STOCK,

  /** The member's ships. */
  SHIP,

  /** The shared stock of the member's units, keyed by unit and lot (REQ-XCH-042). */
  ORG_STOCK,

  /** The member's own bank booking requests, keyed by request id; read-only through the feed. */
  BANK_REQUEST,

  /** The member's own refinery orders, keyed by order id (REQ-XCH-051). */
  REFINERY_ORDER
}
