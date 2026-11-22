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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The words a ship type's name opens with when it names the ship's manufacturer, such as {@code
 * "Esperia"} in {@code "Esperia Talon"} or {@code "RSI"} in {@code "RSI Zeus Mk II ES"}
 * (REQ-HANGAR-019, REQ-P4K-053).
 *
 * <p>Each manufacturer of a ship type contributes the first word of its name, the initials of a
 * name of two or more words ({@code "Roberts Space Industries"} gives {@code "rsi"}, {@code
 * "Consolidated Outland"} gives {@code "co"} for {@code "C.O."}), its nickname and its
 * abbreviation. Words compare as {@link #normalize} folds them. The set is built from every
 * manufacturer of the snapshot, so a ship type linked to the wrong manufacturer or to none still
 * loses its leading manufacturer word.
 */
public final class ShipManufacturerWords {

  /** No manufacturer words: every name is its own short name. */
  public static final ShipManufacturerWords NONE = new ShipManufacturerWords(Set.of());

  /** Splits a name into words at whitespace. */
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** Strips everything outside {@code [a-z0-9]} for the normalised match form. */
  private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]");

  /** Words a manufacturer's initials skip, as in "Musashi Industrial and Starflight Concern". */
  private static final Set<String> NOT_INITIALLED = Set.of("and", "of", "the");

  /** The normalised manufacturer words. */
  private final Set<String> words;

  /**
   * Wraps a word set.
   *
   * @param words the normalised manufacturer words
   */
  private ShipManufacturerWords(@NotNull Set<String> words) {
    this.words = words;
  }

  /**
   * Collects the words of every manufacturer the ship types carry.
   *
   * @param shipTypes a snapshot of the ship types
   * @return the manufacturer words
   */
  @NotNull
  public static ShipManufacturerWords of(@NotNull Collection<ShipType> shipTypes) {
    Set<Manufacturer> manufacturers = Collections.newSetFromMap(new IdentityHashMap<>());
    for (ShipType shipType : shipTypes) {
      Manufacturer manufacturer = shipType.getManufacturer();
      if (manufacturer != null) {
        manufacturers.add(manufacturer);
      }
    }
    Set<String> words = new HashSet<>();
    for (Manufacturer manufacturer : manufacturers) {
      List<String> nameWords = words(manufacturer.getName());
      if (!nameWords.isEmpty()) {
        words.add(nameWords.getFirst());
      }
      List<String> initialled =
          nameWords.stream().filter(word -> !NOT_INITIALLED.contains(word)).toList();
      if (initialled.size() > 1) {
        StringBuilder initials = new StringBuilder();
        initialled.forEach(word -> initials.append(word.charAt(0)));
        words.add(initials.toString());
      }
      addWord(words, manufacturer.getNickname());
      addWord(words, manufacturer.getAbbreviation());
    }
    return words.isEmpty() ? NONE : new ShipManufacturerWords(Set.copyOf(words));
  }

  /**
   * Folds a ship name to lower-case ASCII alphanumerics, e.g. {@code "L-21 Wolf"} to {@code
   * "l21wolf"}.
   *
   * @param name the name, or {@code null}
   * @return the normalised form, empty for {@code null} or a name without alphanumerics
   */
  @NotNull
  public static String normalize(@Nullable String name) {
    if (name == null) {
      return "";
    }
    return NON_ALNUM.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
  }

  /**
   * Whether no manufacturer word is known, so every short name equals its name.
   *
   * @return {@code true} without a word
   */
  public boolean isEmpty() {
    return words.isEmpty();
  }

  /**
   * Drops the leading manufacturer word of a ship name.
   *
   * @param name the ship name
   * @return the name without its first word when that word names a manufacturer and more words
   *     follow, else the trimmed name
   */
  @NotNull
  public String strip(@NotNull String name) {
    String trimmed = name.strip();
    String[] headAndRest = WHITESPACE.split(trimmed, 2);
    if (headAndRest.length < 2 || !words.contains(normalize(headAndRest[0]))) {
      return trimmed;
    }
    return headAndRest[1];
  }

  /**
   * Returns the key two names of one ship agree on: the name without its manufacturer word,
   * normalised.
   *
   * @param name the ship name, or {@code null}
   * @return the key, empty for a {@code null} or blank name
   */
  @NotNull
  public String shortKey(@Nullable String name) {
    return name == null || name.isBlank() ? "" : normalize(strip(name));
  }

  /**
   * Splits a manufacturer name into its normalised words, dropping words that fold to nothing.
   *
   * @param name the manufacturer name, or {@code null}
   * @return the words in order, empty for a {@code null} or blank name
   */
  @NotNull
  private static List<String> words(@Nullable String name) {
    if (name == null || name.isBlank()) {
      return List.of();
    }
    return WHITESPACE
        .splitAsStream(name.strip())
        .map(ShipManufacturerWords::normalize)
        .filter(word -> !word.isEmpty())
        .toList();
  }

  /**
   * Adds the normalised form of a word unless it folds to nothing.
   *
   * @param words the word set
   * @param word the word, or {@code null}
   */
  private static void addWord(@NotNull Set<String> words, @Nullable String word) {
    String normalized = normalize(word);
    if (!normalized.isEmpty()) {
      words.add(normalized);
    }
  }
}
