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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code kind} of a catalogue shop (contract, "Shops"): the folder below {@code PU\Shops}, or
 * {@code shopcomm} / {@code shopfood} below {@code PU\loc}; the values of the LIVE catalogue.
 */
@RequiredArgsConstructor
public enum P4kShopKind implements P4kClosedSet {

  /** An admin office. */
  ADMIN("admin"),

  /** An armour shop. */
  ARMOR("armor"),

  /** A bar. */
  BAR("bar"),

  /** A cargo admin office. */
  CARGO_ADMIN("cargo_admin"),

  /** A clothing shop. */
  CLOTHING("clothing"),

  /** A commodity exchange. */
  COMMEX("commex"),

  /** A convenience store. */
  CONVENIENCE_STORE("convenience_store"),

  /** A courier office. */
  COURIER("courier"),

  /** A ship dealership. */
  DEALERSHIP("dealership"),

  /** An electronics shop. */
  ELECTRONICS("electronics"),

  /** A food stand. */
  FOOD("food"),

  /** A market stall. */
  MARKET_STALLS("market_stalls"),

  /** A pharmacy or clinic. */
  MEDICAL("medical"),

  /** A personal weapon shop. */
  PERSONAL_WEAPON("personalweapon"),

  /** A refinery desk. */
  REFINERY("refinery"),

  /** A service desk, such as Platinum Bay. */
  SERVICE("service"),

  /** A ship part shop. */
  SHIP_PART("shippart"),

  /** A ship weapon shop. */
  SHIP_WEAPON("shipweapon"),

  /** A shop container of a location below {@code PU\loc\shopcomm}. */
  SHOP_COMM("shopcomm"),

  /** A food container of a location below {@code PU\loc\shopfood}. */
  SHOP_FOOD("shopfood");

  /** The value as the catalogue writes it. */
  @NotNull private final String wireValue;

  /**
   * Returns the value as the catalogue writes it.
   *
   * @return the wire value
   */
  @NotNull
  @Override
  public String wireValue() {
    return wireValue;
  }
}
