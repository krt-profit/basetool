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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kLocalisedText;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The field writes the P4K import shares across kinds. */
final class P4kFields {

  /** The longest name key the catalogue stores, matching the {@code name_key} columns. */
  static final int MAX_NAME_KEY_LENGTH = 200;

  /** The shape of a stored name key: no {@code @}, no whitespace. */
  private static final Pattern NAME_KEY = Pattern.compile("^[^@\\s]+$");

  /** The prefix the game's localisation writes in place of a missing translation. */
  static final String MISSING_TRANSLATION = "!";

  /** Non-instantiable holder of static helpers. */
  private P4kFields() {}

  /**
   * Writes a value only where the row holds none; a blank string counts as no value.
   *
   * @param current the row's value
   * @param incoming the catalogue's value
   * @param setter writes the value
   * @param apply whether to write or only report
   * @param <V> the value type
   * @return whether the row's value changes; never {@code true} for a {@code null} incoming value
   */
  @Contract("_, null, _, _ -> false")
  static <V> boolean fillIfNull(
      @Nullable V current, @Nullable V incoming, @NotNull Consumer<V> setter, boolean apply) {
    if (current != null || incoming == null) {
      return false;
    }
    if (incoming instanceof String s && s.isBlank()) {
      return false;
    }
    if (apply) {
      setter.accept(incoming);
    }
    return true;
  }

  /**
   * Writes a value the catalogue leads: a present value replaces a different one, and an absent or
   * blank one never clears the row's.
   *
   * @param current the row's value
   * @param incoming the catalogue's value
   * @param setter writes the value
   * @param apply whether to write or only report
   * @param <V> the value type
   * @return whether the row's value changes
   */
  static <V> boolean replaceIfPresent(
      @Nullable V current, @Nullable V incoming, @NotNull Consumer<V> setter, boolean apply) {
    if (!leads(current, incoming)) {
      return false;
    }
    if (apply) {
      setter.accept(incoming);
    }
    return true;
  }

  /**
   * Whether a catalogue value the P4K leads replaces the row's: it is present, not blank and not
   * the same fact as the row's value.
   *
   * @param current the row's value
   * @param incoming the catalogue's value
   * @return {@code true} when the catalogue value is written
   */
  @Contract("_, null -> false")
  static boolean leads(@Nullable Object current, @Nullable Object incoming) {
    return incoming != null
        && !(incoming instanceof String s && s.isBlank())
        && !sameValue(current, incoming);
  }

  /**
   * Returns the usable German name of a record.
   *
   * @param name the record's localised name, or {@code null}
   * @return the stripped German text, or {@code null} when it is absent, blank or the game's
   *     missing-translation marker (a text starting with {@value #MISSING_TRANSLATION})
   */
  @Nullable
  static String germanName(@Nullable P4kLocalisedText name) {
    String de = P4kLocalisedText.deOf(name);
    if (de == null) {
      return null;
    }
    String stripped = de.strip();
    return stripped.isEmpty() || stripped.startsWith(MISSING_TRANSLATION) ? null : stripped;
  }

  /**
   * Replaces a stored name key with the catalogue's usable one; the catalogue is the only source of
   * name keys, so it always wins.
   *
   * @param current the row's name key
   * @param incoming the catalogue's raw localisation key
   * @param setter writes the name key
   * @param apply whether to write or only report
   * @return whether the row's name key changes
   */
  static boolean refreshNameKey(
      @Nullable String current,
      @Nullable String incoming,
      @NotNull Consumer<String> setter,
      boolean apply) {
    String stored = storedNameKey(incoming);
    if (stored == null || stored.equals(current)) {
      return false;
    }
    if (apply) {
      setter.accept(stored);
    }
    return true;
  }

  /**
   * Whether two values are the same fact; decimals compare by value, so {@code 8.0} equals {@code
   * 8.000}.
   *
   * @param a one value
   * @param b the other value
   * @return {@code true} when both are {@code null} or equal
   */
  static boolean sameValue(@Nullable Object a, @Nullable Object b) {
    if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
      return x.compareTo(y) == 0;
    }
    return Objects.equals(a, b);
  }

  /**
   * Renders one {@code P4K_DISAGREEMENT} detail entry.
   *
   * @param column the led column
   * @param stored the value that stays
   * @param source the other source's differing value
   * @return the {@code column=…;stored=…;source=…} entry
   */
  @NotNull
  static String disagreement(
      @NotNull String column, @Nullable String stored, @Nullable String source) {
    return SyncReportDetails.of("column", column)
        .with("stored", stored)
        .with("source", source)
        .toString();
  }

  /**
   * Converts a raw localisation key to its stored form.
   *
   * @param raw the raw key, e.g. {@code @item_NameBEHR_LaserCannon_S2}
   * @return the key without the leading {@code @}, or {@code null} when it is blank, a {@code LOC_}
   *     placeholder, longer than {@value #MAX_NAME_KEY_LENGTH} characters or holds an {@code @} or
   *     whitespace
   */
  @Nullable
  static String storedNameKey(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    String key = raw.strip();
    if (key.startsWith("@")) {
      key = key.substring(1);
    }
    if (key.isEmpty()
        || key.length() > MAX_NAME_KEY_LENGTH
        || key.regionMatches(true, 0, "LOC_", 0, 4)
        || !NAME_KEY.matcher(key).matches()) {
      return null;
    }
    return key;
  }
}
