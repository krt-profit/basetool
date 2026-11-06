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

import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The field writes the P4K import shares across kinds. */
final class P4kFields {

  /** The longest name key the catalogue stores, matching the {@code name_key} columns. */
  static final int MAX_NAME_KEY_LENGTH = 200;

  /** The shape of a stored name key: no {@code @}, no whitespace. */
  private static final Pattern NAME_KEY = Pattern.compile("^[^@\\s]+$");

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
   * @return whether the row's value changes
   */
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
