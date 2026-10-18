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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentType;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The category, component-type, size, grade and class filters of the Lager item view (REQ-INV-063).
 *
 * <p>Every list is distinct, free of {@code null} and blank values, and empty when it narrows
 * nothing. A size, grade or class filter narrows to ship components, the only items whose size,
 * grade and class the Lager shows (REQ-INV-061, REQ-INV-062).
 *
 * @param kinds the item categories to narrow to
 * @param componentTypes the ship component types to narrow to
 * @param sizes the component sizes to narrow to
 * @param grades the component grades to narrow to, trimmed
 * @param itemClasses the component classes to narrow to
 */
public record ItemAttributeFilter(
    @NotNull @Unmodifiable List<GameItemKind> kinds,
    @NotNull @Unmodifiable List<ShipComponentType> componentTypes,
    @NotNull @Unmodifiable List<Integer> sizes,
    @NotNull @Unmodifiable List<String> grades,
    @NotNull @Unmodifiable List<ShipComponentClass> itemClasses) {

  /** Narrows nothing. */
  public static final ItemAttributeFilter NONE =
      new ItemAttributeFilter(null, null, null, null, null);

  private static final List<String> COMPONENT_WIKI_TYPES =
      Arrays.stream(ShipComponentType.values()).map(ShipComponentType::getWikiType).toList();

  /**
   * Normalises the request values: {@code null} lists become empty, {@code null} and blank values
   * are dropped, grades are trimmed and duplicates removed.
   *
   * @param kinds the item categories, may be {@code null}
   * @param componentTypes the ship component types, may be {@code null}
   * @param sizes the component sizes, may be {@code null}
   * @param grades the component grades, may be {@code null}
   * @param itemClasses the component classes, may be {@code null}
   */
  public ItemAttributeFilter(
      @Nullable List<GameItemKind> kinds,
      @Nullable List<ShipComponentType> componentTypes,
      @Nullable List<Integer> sizes,
      @Nullable List<String> grades,
      @Nullable List<ShipComponentClass> itemClasses) {
    this.kinds = distinctNonNull(kinds);
    this.componentTypes = distinctNonNull(componentTypes);
    this.sizes = distinctNonNull(sizes);
    this.grades =
        grades == null
            ? List.of()
            : grades.stream()
                .map(StringNormalization::trimToNull)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    this.itemClasses = distinctNonNull(itemClasses);
  }

  /**
   * Whether no dimension narrows the result.
   *
   * @return {@code true} when every list is empty
   */
  public boolean isEmpty() {
    return kinds.isEmpty()
        && componentTypes.isEmpty()
        && sizes.isEmpty()
        && grades.isEmpty()
        && itemClasses.isEmpty();
  }

  /**
   * Gates the category clause.
   *
   * @return whether a category narrows the result
   */
  public boolean hasKinds() {
    return !kinds.isEmpty();
  }

  /**
   * The category query parameter.
   *
   * @return the categories, or {@code null} when none narrows the result
   */
  public @Nullable List<GameItemKind> kindsOrNull() {
    return hasKinds() ? kinds : null;
  }

  /**
   * Gates the wiki-type clause, which a component-type, size, grade or class filter sets.
   *
   * @return whether the result narrows to some wiki types
   */
  public boolean hasWikiTypes() {
    return !componentTypes.isEmpty()
        || !sizes.isEmpty()
        || !grades.isEmpty()
        || !itemClasses.isEmpty();
  }

  /**
   * The wiki-type query parameter: the chosen component types, or every component type when only a
   * size, grade or class narrows the result.
   *
   * @return the wiki types, or {@code null} when none narrows the result
   */
  public @Nullable List<String> wikiTypesOrNull() {
    if (!componentTypes.isEmpty()) {
      return componentTypes.stream().map(ShipComponentType::getWikiType).toList();
    }
    return hasWikiTypes() ? COMPONENT_WIKI_TYPES : null;
  }

  /**
   * Gates the size clause.
   *
   * @return whether a size narrows the result
   */
  public boolean hasSizes() {
    return !sizes.isEmpty();
  }

  /**
   * The size query parameter.
   *
   * @return the sizes, or {@code null} when none narrows the result
   */
  public @Nullable List<Integer> sizesOrNull() {
    return hasSizes() ? sizes : null;
  }

  /**
   * Gates the grade clause.
   *
   * @return whether a grade narrows the result
   */
  public boolean hasGrades() {
    return !grades.isEmpty();
  }

  /**
   * The grade query parameter.
   *
   * @return the trimmed grades, or {@code null} when none narrows the result
   */
  public @Nullable List<String> gradesOrNull() {
    return hasGrades() ? grades : null;
  }

  /**
   * Gates the class clause.
   *
   * @return whether a component class narrows the result
   */
  public boolean hasItemClasses() {
    return !itemClasses.isEmpty();
  }

  /**
   * The class query parameter.
   *
   * @return the component classes, or {@code null} when none narrows the result
   */
  public @Nullable List<ShipComponentClass> itemClassesOrNull() {
    return hasItemClasses() ? itemClasses : null;
  }

  private static <T> @NotNull @Unmodifiable List<T> distinctNonNull(@Nullable List<T> values) {
    return values == null
        ? List.of()
        : values.stream().filter(Objects::nonNull).distinct().toList();
  }
}
