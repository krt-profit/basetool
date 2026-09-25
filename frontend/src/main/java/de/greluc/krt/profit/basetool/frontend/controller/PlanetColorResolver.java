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

package de.greluc.krt.profit.basetool.frontend.controller;

import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Maps a UEX planet name to a stable CSS class that tints terminal columns on the materials matrix.
 *
 * <p>Known planets use a hand-picked class; others hash (system, planet) into one of {@link
 * #HASH_PALETTE_SIZE} {@code planet-hash-*} classes. A blank planet yields {@link #UNKNOWN_CLASS}.
 */
final class PlanetColorResolver {

  /** CSS class applied to terminals that are not attached to any planet system. */
  static final String UNKNOWN_CLASS = "planet-unknown";

  /** Number of hash-fallback palette slots. Must match the CSS rules defined in the template. */
  static final int HASH_PALETTE_SIZE = 12;

  /**
   * Canonical map from a normalised planet name (lower-case, trimmed) to its CSS class. The values
   * intentionally do not include the {@code planet-} prefix - that is added in {@link
   * #cssClassFor(String, String)} so the constants here stay short and the prefix can be changed in
   * one place if the CSS naming scheme ever shifts.
   */
  private static final Map<String, String> CANONICAL =
      Map.ofEntries(
          Map.entry("hurston", "hurston"),
          Map.entry("crusader", "crusader"),
          Map.entry("arccorp", "arccorp"),
          Map.entry("microtech", "microtech"),
          Map.entry("pyro i", "pyro-1"),
          Map.entry("pyro ii", "pyro-2"),
          Map.entry("monox", "pyro-2"),
          Map.entry("pyro iii", "pyro-3"),
          Map.entry("bloom", "pyro-3"),
          Map.entry("pyro iv", "pyro-4"),
          Map.entry("terminus", "pyro-4"),
          Map.entry("pyro v", "pyro-5"),
          Map.entry("vatra", "pyro-5"),
          Map.entry("pyro vi", "pyro-6"),
          Map.entry("adir", "pyro-6"),
          Map.entry("terra", "terra"),
          Map.entry("delamar", "delamar"));

  private PlanetColorResolver() {}

  /**
   * Resolves the CSS class of a terminal column's planet tint.
   *
   * @param starSystemName parent star system; may be {@code null} or blank
   * @param planetName effective planet name; {@code null} or blank yields {@link #UNKNOWN_CLASS}
   * @return a class such as {@code planet-hurston}, {@code planet-hash-3} or {@link
   *     #UNKNOWN_CLASS}; never blank
   */
  @NotNull
  static String cssClassFor(@Nullable String starSystemName, @Nullable String planetName) {
    if (planetName == null || planetName.isBlank()) {
      return UNKNOWN_CLASS;
    }
    String normalised = planetName.trim().toLowerCase(Locale.ROOT);
    String canonical = CANONICAL.get(normalised);
    if (canonical != null) {
      return "planet-" + canonical;
    }
    int index = stableHashIndex(starSystemName, normalised, HASH_PALETTE_SIZE);
    return "planet-hash-" + index;
  }

  /**
   * Computes a deterministic, non-negative bucket index from the (system, planet) tuple via {@link
   * String#hashCode()}.
   *
   * @param starSystemName star-system part of the key; {@code null} or blank counts as empty
   * @param normalisedPlanet the normalized planet name
   * @param buckets palette size; must be positive
   * @return bucket index in {@code [0, buckets)}
   */
  private static int stableHashIndex(
      @Nullable String starSystemName, @NotNull String normalisedPlanet, int buckets) {
    String system = starSystemName != null ? starSystemName.trim().toLowerCase(Locale.ROOT) : "";
    int h = 31 * system.hashCode() + normalisedPlanet.hashCode();
    int mod = h % buckets;
    return mod < 0 ? mod + buckets : mod;
  }
}
