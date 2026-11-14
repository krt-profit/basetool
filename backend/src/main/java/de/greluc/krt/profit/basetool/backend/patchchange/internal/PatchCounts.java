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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The counts one or several patch-change effects report, at most one value per {@link PatchCount}
 * (REQ-PATCH-005). Immutable; merging two sets adds the values of a kind both report.
 */
public final class PatchCounts {

  /** The counts by kind, in the kinds' declaration order. */
  private final @NotNull @Unmodifiable Map<PatchCount, Long> values;

  /**
   * Wraps a map the caller hands over and no longer touches.
   *
   * @param values the counts by kind
   */
  private PatchCounts(@NotNull EnumMap<PatchCount, Long> values) {
    this.values = Collections.unmodifiableMap(values);
  }

  /**
   * Returns the empty set, which an effect reports when its option is off.
   *
   * @return no counts
   */
  public static @NotNull PatchCounts none() {
    return new PatchCounts(new EnumMap<>(PatchCount.class));
  }

  /**
   * Returns a set of one count.
   *
   * @param kind what is counted
   * @param value the count, zero or more
   * @return the set
   * @throws IllegalArgumentException when the value is negative
   */
  public static @NotNull PatchCounts of(@NotNull PatchCount kind, long value) {
    if (value < 0) {
      throw new IllegalArgumentException("A patch-change count is never negative: " + value);
    }
    EnumMap<PatchCount, Long> values = new EnumMap<>(PatchCount.class);
    values.put(kind, value);
    return new PatchCounts(values);
  }

  /**
   * Restores a set from stored counts.
   *
   * @param values the counts by kind
   * @return the set
   */
  public static @NotNull PatchCounts copyOf(@NotNull Map<PatchCount, Long> values) {
    EnumMap<PatchCount, Long> copy = new EnumMap<>(PatchCount.class);
    copy.putAll(values);
    return new PatchCounts(copy);
  }

  /**
   * Merges this set with another, adding the values of a kind both carry.
   *
   * @param other the other set
   * @return the merged set
   */
  public @NotNull PatchCounts plus(@NotNull PatchCounts other) {
    EnumMap<PatchCount, Long> merged = new EnumMap<>(PatchCount.class);
    merged.putAll(values);
    other.values.forEach((kind, value) -> merged.merge(kind, value, Long::sum));
    return new PatchCounts(merged);
  }

  /**
   * Returns the counts by kind.
   *
   * @return the counts in the kinds' declaration order; unmodifiable, and the map behind it is
   *     owned by this set and never changes
   */
  public @NotNull @Unmodifiable Map<PatchCount, Long> asMap() {
    return values;
  }

  /**
   * Returns the counts as the wire carries them.
   *
   * @return one entry per kind, in the kinds' declaration order
   */
  public @NotNull @Unmodifiable List<PatchCountDto> toDtos() {
    return values.entrySet().stream()
        .map(entry -> new PatchCountDto(entry.getKey(), entry.getValue()))
        .toList();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PatchCounts counts && values.equals(counts.values);
  }

  @Override
  public int hashCode() {
    return values.hashCode();
  }

  @Override
  public String toString() {
    return values.toString();
  }
}
