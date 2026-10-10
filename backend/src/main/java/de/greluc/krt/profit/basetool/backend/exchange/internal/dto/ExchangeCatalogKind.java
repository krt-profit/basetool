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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

/** The catalogue a {@code catalog/resolve} request looks its references up in (REQ-XCH-012). */
public enum ExchangeCatalogKind {

  /** A blueprint product, keyed by its normalized product key. */
  BLUEPRINT,

  /** A game item, keyed by its id. */
  ITEM,

  /** A material, keyed by its id. */
  MATERIAL,

  /** A ship type, keyed by its id. */
  SHIP_TYPE
}
