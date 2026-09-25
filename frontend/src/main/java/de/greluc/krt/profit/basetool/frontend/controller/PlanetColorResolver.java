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
 * Maps a UEX planet name to a stable CSS class used to tint terminal columns on the materials
 * matrix.
 *
 * <p>The resolver is intentionally pure: it has no dependencies, no caching, and no per-request
 * state. Two paths produce a class:
 *
 * <ol>
 *   <li><b>Canonical map</b> - well-known Star Citizen planets (Stanton, Pyro, Terra, Nyx) map to a
 *       hand-picked, semantically appropriate class (e.g. Hurston gets a rust tint). Lookup is
 *       case-insensitive on the planet name; the star-system name is currently unused but kept on
 *       the API so future name collisions across systems can be disambiguated without a signature
 *       change.
 *   <li><b>Hash fallback</b> - for any planet not in the canonical map, the (system, planet) tuple
 *       is hashed into one of {@link #HASH_PALETTE_SIZE} numbered classes ({@code planet-hash-0} ..
 *       {@code planet-hash-N-1}). Same tuple always produces the same class across requests so the
 *       UI stays visually stable.
 * </ol>
 *
 * <p>{@code null} / blank planet names resolve to {@link #UNKNOWN_CLASS}, which the stylesheet
 * leaves unstyled (terminal keeps the default neutral header background).
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
   * Resolves a CSS class name for the planet tint of a terminal column.
   *
   * @param starSystemName parent star system; reserved for future disambiguation, may be {@code
   *     null} or blank
   * @param planetName effective planet name as resolved by the backend matrix query (direct, via
   *     moon, or via like-named orbit); {@code null}/blank yields {@link #UNKNOWN_CLASS}
   * @return a CSS class name like {@code planet-hurston}, {@code planet-hash-3}, or {@link
   *     #UNKNOWN_CLASS}; never {@code null} and never blank
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
   * Computes a non-negative, stable bucket index from the (system, planet) tuple by mixing the two
   * strings via {@code String.hashCode()} and taking the absolute value modulo {@code buckets}.
   * Deterministic across JVM runs because {@link String#hashCode()} is specified.
   *
   * @param starSystemName star-system part of the key; may be {@code null}/blank (treated as empty)
   * @param normalisedPlanet pre-normalised planet name; must not be {@code null}
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
