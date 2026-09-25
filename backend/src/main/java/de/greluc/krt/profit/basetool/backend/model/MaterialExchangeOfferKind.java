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
 * Discriminator for the two kinds of {@link MaterialExchangeOffer} (REQ-MARKET-002 /
 * REQ-MARKET-012).
 *
 * <p>A {@link #MATERIAL} offer reads material, quality and amount live from its Lager row. An
 * {@link #ITEM} offer names a blueprint product by {@code product_key} with an owner-stated
 * quantity and carries no quality or location.
 */
public enum MaterialExchangeOfferKind {

  /** An offer backed by a Lager row; material/quality/amount are read live from the item. */
  MATERIAL,

  /**
   * An offer for a craftable item (blueprint product); the owner states the quantity, no quality.
   */
  ITEM
}
