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

package de.greluc.krt.profit.basetool.frontend.hangar.model;

import java.util.Arrays;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * The origins the backend knows for a ship (REQ-HANGAR-017), named as the backend names them; the
 * hangar pages read a filter and a form choice through it.
 */
public enum ShipOrigin {
  /** Bought on the RSI website. */
  PLEDGE,
  /** Bought with in-game currency. */
  IN_GAME,
  /** Not chosen yet. */
  UNKNOWN;

  /**
   * Reads a request value leniently, so a crafted value means "none" instead of an error.
   *
   * @param raw the raw value, e.g. a query parameter
   * @return the origin of that exact name, or {@code null} for a blank or unknown value
   */
  public static @Nullable ShipOrigin parse(@Nullable String raw) {
    return Arrays.stream(values())
        .filter(origin -> origin.name().equals(raw))
        .findFirst()
        .orElse(null);
  }

  /**
   * Reads the origin an add or edit form chose, which offers only {@link #PLEDGE} and {@link
   * #IN_GAME}.
   *
   * @param raw the submitted value
   * @return the chosen origin, or {@code null} for none or any other value
   */
  public static @Nullable ShipOrigin choiceOf(@Nullable String raw) {
    ShipOrigin origin = parse(raw);
    return origin == UNKNOWN ? null : origin;
  }

  /**
   * Names an optional origin as the backend's write payloads carry it.
   *
   * @param origin the origin, or {@code null}
   * @return the constant's name, or {@code null} for none
   */
  @Contract("null -> null; !null -> !null")
  public static @Nullable String nameOf(@Nullable ShipOrigin origin) {
    return origin == null ? null : origin.name();
  }
}
