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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kManufacturerDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the brand a ship's English name begins with (REQ-P4K-059): the manufacturer whose name's
 * first word, code or class name is the ship name's first word, when exactly one manufacturer
 * carries that word. The game files give some ships another brand's GUID ("Esperia Stinger" the
 * Vanduul GUID); the brand in the name wins over it.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class P4kShipBrands {

  /** The matched manufacturers by the folded words that name them, keyed by row id. */
  @NotNull private final Map<String, Map<UUID, Manufacturer>> byWord;

  /**
   * Collects the words that name each matched manufacturer; a borrowed name names no brand.
   *
   * @param manufacturers the catalogue's manufacturers
   * @param context the run, which resolves a manufacturer GUID to its matched row
   * @return the brands by word
   */
  @NotNull
  static P4kShipBrands of(
      @NotNull List<P4kManufacturerDto> manufacturers, @NotNull P4kImportContext context) {
    List<P4kManufacturerDto> canonical =
        manufacturers.stream()
            .filter(record -> !P4kManufacturerRecordPath.isAuxiliary(record.recordPath()))
            .toList();
    P4kManufacturerFamilies families = P4kManufacturerFamilies.of(canonical);
    Map<String, Map<UUID, Manufacturer>> byWord = new HashMap<>();
    for (P4kManufacturerDto record : canonical) {
      Manufacturer row = context.manufacturer(record.guid());
      if (row == null || row.getId() == null) {
        continue;
      }
      String nameWord =
          families.lenderOf(record.parsedGuid()) == null ? firstWord(record.nameEn()) : "";
      Stream.of(nameWord, fold(record.code()), fold(record.className()))
          .filter(word -> !word.isEmpty())
          .forEach(
              word -> byWord.computeIfAbsent(word, _ -> new HashMap<>()).put(row.getId(), row));
    }
    return new P4kShipBrands(byWord);
  }

  /**
   * Returns the brand a ship's English name begins with.
   *
   * @param shipName the ship's English name, or {@code null}
   * @return the only manufacturer the first word names, or {@code null} when it names none or
   *     several
   */
  @Nullable
  Manufacturer named(@Nullable String shipName) {
    Map<UUID, Manufacturer> named = byWord.get(firstWord(shipName));
    return named == null || named.size() != 1 ? null : named.values().iterator().next();
  }

  /**
   * Returns the folded first word of a name.
   *
   * @param name the name, or {@code null}
   * @return the first word, lower-cased, empty when there is none
   */
  @NotNull
  private static String firstWord(@Nullable String name) {
    String folded = fold(name);
    int space = folded.indexOf(' ');
    return space < 0 ? folded : folded.substring(0, space);
  }

  /**
   * Folds a text to its comparison key.
   *
   * @param text the text, or {@code null}
   * @return the stripped, lower-cased text, empty when absent
   */
  @NotNull
  private static String fold(@Nullable String text) {
    return P4kManufacturerFamilies.fold(text);
  }
}
