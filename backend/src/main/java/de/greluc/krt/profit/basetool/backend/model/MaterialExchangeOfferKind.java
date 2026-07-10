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
 * Discriminator for the two kinds of {@link MaterialExchangeOffer} on the Materialbörse
 * (REQ-MARKET-002 / REQ-MARKET-012).
 *
 * <p>A {@link #MATERIAL} offer is a thin overlay on a Lager row ({@code inventory_item}): its
 * material, quality and amount are read <b>live</b> from the linked item, the client never sets
 * them (ADR-0082 D1). An {@link #ITEM} offer has no backing stock row — it references a blueprint
 * product (an "item for which a blueprint exists", #1185) by its normalized {@code product_key},
 * and the offering player <b>states the quantity</b> at release time (there is no live source to
 * read it from). An item offer carries no quality and no location. The exactly-one-branch integrity
 * is enforced at the DB level (V213 {@code CHECK}) and mirrored by the entity's nullability.
 */
public enum MaterialExchangeOfferKind {

  /** An offer backed by a Lager row; material/quality/amount are read live from the item. */
  MATERIAL,

  /**
   * An offer for a craftable item (blueprint product); the owner states the quantity, no quality.
   */
  ITEM
}
