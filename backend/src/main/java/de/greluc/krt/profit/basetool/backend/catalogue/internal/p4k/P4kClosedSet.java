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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A closed value set of the catalogue that the schema keeps as a string (contract, "Game texts"): a
 * value the Basetool does not know reads as {@code null} (REQ-P4K-003).
 */
public interface P4kClosedSet {

  /**
   * Returns the value as the catalogue writes it.
   *
   * @return the wire value, e.g. {@code player}
   */
  @NotNull
  String wireValue();

  /**
   * Reads a wire value into its constant.
   *
   * @param type the enum of the set
   * @param raw the wire value, or {@code null}
   * @param <E> the enum type
   * @return the constant, or {@code null} for {@code null} and for a value the set does not know
   */
  @Nullable
  static <E extends Enum<E> & P4kClosedSet> E parse(@NotNull Class<E> type, @Nullable String raw) {
    if (raw == null) {
      return null;
    }
    for (E constant : type.getEnumConstants()) {
      if (constant.wireValue().equals(raw)) {
        return constant;
      }
    }
    return null;
  }

  /**
   * Whether a wire value is present but unknown to the set.
   *
   * @param type the enum of the set
   * @param raw the wire value, or {@code null}
   * @param <E> the enum type
   * @return {@code true} when {@code raw} is non-null and no constant has it
   */
  static <E extends Enum<E> & P4kClosedSet> boolean isUnknown(
      @NotNull Class<E> type, @Nullable String raw) {
    return raw != null && parse(type, raw) == null;
  }
}
