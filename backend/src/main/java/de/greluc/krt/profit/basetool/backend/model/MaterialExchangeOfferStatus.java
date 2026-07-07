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

package de.greluc.krt.profit.basetool.backend.model;

/**
 * Lifecycle state of a {@link MaterialExchangeOffer} on the Materialbörse trade board.
 *
 * <p>An offer is {@link #ACTIVE} while it is publicly listed and {@link #DEACTIVATED} once the
 * owner takes it off the board (un-checking "Für Börse freigeben" or pressing "Angebot
 * deaktivieren"). The partial-unique constraint {@code (inventory_item_id) WHERE status = 'ACTIVE'}
 * allows exactly one active offer per Lager row, so re-releasing an item re-activates rather than
 * duplicates. The {@code MARKET} business-metric gauge counts {@link #ACTIVE} rows only.
 */
public enum MaterialExchangeOfferStatus {

  /** Publicly listed on the board and visible to every member. */
  ACTIVE,

  /** Taken off the board by the owner; retained for the audit trail but never listed. */
  DEACTIVATED
}
