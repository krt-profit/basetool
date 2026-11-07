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

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/**
 * The grade letter of a P4K item, read from its raw DataForge grade (REQ-P4K-018, ADR-0337).
 *
 * <p>The letter follows the integer only on the five component types the catalogue contract
 * verified; every other type has no letter from the P4K, so the SC Wiki fills it.
 */
final class P4kGradeMapping {

  /** The letters of the grades 1 to 4, in order. */
  private static final List<String> LETTERS = List.of("A", "B", "C", "D");

  /** The lowest grade with a letter. */
  private static final int LOWEST = 1;

  /** The item types whose grade maps onto a letter, lower-cased. */
  private static final Set<String> MAPPED_TYPES =
      Set.of("Cooler", "PowerPlant", "QuantumDrive", "Radar", "Shield").stream()
          .map(type -> type.toLowerCase(Locale.ROOT))
          .collect(Collectors.toUnmodifiableSet());

  /** Non-instantiable holder of static helpers. */
  private P4kGradeMapping() {}

  /**
   * Maps a raw grade onto its letter.
   *
   * @param type the catalogue's item {@code type}, matched ignoring case
   * @param raw the raw DataForge grade
   * @return {@code A} to {@code D} for a grade of 1 to 4 on a mapped type, else {@code null}
   */
  @Nullable
  static String letter(@Nullable String type, @Nullable Integer raw) {
    if (type == null
        || raw == null
        || isUnknown(raw)
        || !MAPPED_TYPES.contains(type.strip().toLowerCase(Locale.ROOT))) {
      return null;
    }
    return LETTERS.get(raw - LOWEST);
  }

  /**
   * Tells whether a raw grade lies outside the range the contract gives a letter to.
   *
   * @param raw the raw DataForge grade
   * @return whether the grade is present and outside 1 to 4
   */
  static boolean isUnknown(@Nullable Integer raw) {
    return raw != null && (raw < LOWEST || raw >= LOWEST + LETTERS.size());
  }
}
