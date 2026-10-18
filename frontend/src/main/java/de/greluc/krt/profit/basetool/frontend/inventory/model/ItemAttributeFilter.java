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

package de.greluc.krt.profit.basetool.frontend.inventory.model;

import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The category, component-type, size and grade filters of the item view (REQ-INV-063).
 *
 * <p>Every list is distinct, free of {@code null} and blank values, and empty when it narrows
 * nothing.
 *
 * @param kinds the item category names to narrow to
 * @param componentTypes the ship component type names to narrow to
 * @param sizes the component sizes to narrow to
 * @param grades the component grades to narrow to, trimmed
 */
public record ItemAttributeFilter(
    @NotNull @Unmodifiable List<String> kinds,
    @NotNull @Unmodifiable List<String> componentTypes,
    @NotNull @Unmodifiable List<Integer> sizes,
    @NotNull @Unmodifiable List<String> grades) {

  /** No narrowing at all. */
  public static final ItemAttributeFilter NONE = new ItemAttributeFilter(null, null, null, null);

  /**
   * Normalises the request values: {@code null} lists become empty, {@code null} and blank values
   * are dropped, strings are trimmed and duplicates removed.
   *
   * @param kinds the item category names, may be {@code null}
   * @param componentTypes the ship component type names, may be {@code null}
   * @param sizes the component sizes, may be {@code null}
   * @param grades the component grades, may be {@code null}
   */
  public ItemAttributeFilter(
      @Nullable List<String> kinds,
      @Nullable List<String> componentTypes,
      @Nullable List<Integer> sizes,
      @Nullable List<String> grades) {
    this.kinds = cleanStrings(kinds);
    this.componentTypes = cleanStrings(componentTypes);
    this.sizes =
        sizes == null ? List.of() : sizes.stream().filter(Objects::nonNull).distinct().toList();
    this.grades = cleanStrings(grades);
  }

  /**
   * Whether no dimension narrows the result.
   *
   * @return {@code true} when every list is empty
   */
  public boolean isEmpty() {
    return kinds.isEmpty() && componentTypes.isEmpty() && sizes.isEmpty() && grades.isEmpty();
  }

  private static @NotNull @Unmodifiable List<String> cleanStrings(@Nullable List<String> values) {
    return values == null
        ? List.of()
        : values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .toList();
  }
}
