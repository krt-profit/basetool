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

package de.greluc.krt.profit.basetool.backend.inventory.api;

/**
 * Why stock was booked into an org unit's pool, as the Lager records it in the audit details of the
 * pool commands (REQ-INV-057). Not the {@link StockChangeReason}, which only names why a row was
 * lowered for the offer ratchet.
 */
public enum StockBookingReason {

  /** A member's donation, received by the receiving unit. */
  DONATION,

  /** A delivery bought from an external source and accepted by the raising unit. */
  PURCHASE
}
