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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import java.util.Map;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The head of a catalogue: {@code schemaVersion}, {@code source} and {@code completeness}.
 *
 * @param schemaVersion the shape version, or {@code null} when the file names none
 * @param source the source block, or {@code null} when the file has none
 * @param completeness the completeness per catalogue field name; empty when the file has none
 */
public record P4kCatalogueHead(
    @Nullable Integer schemaVersion,
    @Nullable P4kSource source,
    @NotNull @Unmodifiable Map<String, P4kKindCompleteness> completeness) {

  /**
   * Copies the completeness map without its {@code null} entries.
   *
   * @param schemaVersion the shape version
   * @param source the source block
   * @param completeness the completeness per catalogue field name
   */
  public P4kCatalogueHead {
    completeness =
        completeness.entrySet().stream()
            .filter(e -> e.getKey() != null && e.getValue() != null)
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /**
   * Whether a kind is complete; a kind without an entry reads as partial.
   *
   * @param kind the kind
   * @return {@code true} when the catalogue states the kind complete
   */
  public boolean isComplete(@NotNull P4kKind kind) {
    return isComplete(kind.getCatalogueField());
  }

  /**
   * Whether the kind a top-level field holds is complete; a field without an entry reads as
   * partial.
   *
   * @param catalogueField the field, e.g. {@code paints}
   * @return {@code true} when the catalogue states the kind complete
   */
  public boolean isComplete(@NotNull String catalogueField) {
    P4kKindCompleteness entry = completeness.get(catalogueField);
    return entry != null && entry.isComplete();
  }

  /**
   * Returns the Build of the source, if both are present.
   *
   * @return the Build, or {@code null}
   */
  @Nullable
  public P4kBuild build() {
    return source == null ? null : source.build();
  }
}
