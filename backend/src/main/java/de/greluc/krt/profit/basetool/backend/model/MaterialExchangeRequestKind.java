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
 * Discriminator for the two kinds of {@link MaterialExchangeRequest} on the Materialbörse — a
 * wanted-listing (Gesuch) for a material or for a craftable item (REQ-MARKET-015). It is the
 * request-side sibling of {@link MaterialExchangeOfferKind}, kept as its own enum so the request
 * aggregate stays decoupled from the offer aggregate (ADR-0116).
 *
 * <p>A {@link #MATERIAL} request names a catalogue {@link Material} the member wants, in a stated
 * quantity (SCU or Stück per the material's {@link Material#getQuantityType() quantity type}). An
 * {@link #ITEM} request names a craftable item ("an item for which a blueprint exists") by its
 * normalized {@code product_key}, in a stated whole-piece quantity. Either kind may additionally
 * carry an optional minimum desired quality (0–1000). Unlike an offer there is <b>no backing Lager
 * row</b>: the member states the identity and quantity directly. The exactly-one-branch integrity
 * is enforced at the DB level (V224 {@code CHECK}) and mirrored by the entity's nullability.
 */
public enum MaterialExchangeRequestKind {

  /** A request for a catalogue material, in a stated SCU/piece quantity. */
  MATERIAL,

  /** A request for a craftable item (blueprint product), in a stated whole-piece quantity. */
  ITEM
}
