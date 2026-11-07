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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Normalises place names for the starmap linking and the visibility rule (REQ-P4K-035). */
public final class StarmapNames {

  /** Combining marks left after decomposition. */
  private static final Pattern MARKS = Pattern.compile("\\p{M}+");

  /** Runs of characters that are neither letters nor digits. */
  private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

  /** Non-instantiable holder of static helpers. */
  private StarmapNames() {}

  /**
   * Normalises a name: lower case, accents dropped, every run of other characters one space.
   *
   * @param name the name, or {@code null}
   * @return the normalised name, or {@code null} when nothing is left
   */
  @Contract(pure = true)
  @Nullable
  public static String normalise(@Nullable String name) {
    if (name == null) {
      return null;
    }
    String plain = MARKS.matcher(Normalizer.normalize(name, Normalizer.Form.NFKD)).replaceAll("");
    String spaced = SEPARATORS.matcher(plain.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    return spaced.isEmpty() ? null : spaced;
  }

  /**
   * Builds a lookup key of a name within its star system.
   *
   * @param starSystem the star system's name, or {@code null}
   * @param name the name
   * @return the key
   */
  @NotNull
  static String key(@Nullable String starSystem, @NotNull String name) {
    return normalise(starSystem) + "|" + normalise(name);
  }
}
