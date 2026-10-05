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

package de.greluc.krt.profit.basetool.backend.kernel;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The one range every material quality and every quality floor lives in: {@value #MIN} to {@value
 * #MAX} inclusive (REQ-DATA-023).
 */
@Slf4j
public final class Quality {

  /** The lowest quality a material row or a floor can carry. */
  public static final int MIN = 0;

  /** The highest quality a material row or a floor can carry. */
  public static final int MAX = 1000;

  private Quality() {}

  /**
   * Whether {@code quality} lies inside the range.
   *
   * @param quality the value to test
   * @return {@code true} for {@value #MIN} to {@value #MAX} inclusive
   */
  public static boolean isInRange(int quality) {
    return quality >= MIN && quality <= MAX;
  }

  /**
   * Clamps an imported quality into the range; for external catalogue data, never for user input.
   *
   * @param quality the imported value, possibly {@code null}
   * @return {@code null} for {@code null}, otherwise the value clamped to the range
   */
  @Nullable
  @Contract("null -> null; !null -> !null")
  public static Integer clamp(@Nullable Integer quality) {
    return quality == null ? null : Math.clamp(quality, MIN, MAX);
  }

  /**
   * Clamps an imported fractional quality bound into the range; for external catalogue data only.
   *
   * @param quality the imported value, possibly {@code null}
   * @return {@code null} for {@code null}, otherwise the value clamped to the range
   */
  @Nullable
  @Contract("null -> null; !null -> !null")
  public static Double clamp(@Nullable Double quality) {
    return quality == null ? null : Math.clamp(quality, (double) MIN, (double) MAX);
  }

  /**
   * Clamps a quality read from an external catalogue and logs a warning when it had to.
   *
   * @param quality the imported value, possibly {@code null}
   * @param field the catalogue field it came from, for the log line
   * @return the value clamped to the range, {@code null} for {@code null}
   */
  @Nullable
  @Contract("null, _ -> null; !null, _ -> !null")
  public static Integer clampImported(@Nullable Integer quality, @NotNull String field) {
    Integer clamped = clamp(quality);
    if (quality != null && !quality.equals(clamped)) {
      log.warn(
          "Imported quality {} in {} lies outside 0..1000; stored as {}", quality, field, clamped);
    }
    return clamped;
  }

  /**
   * Clamps a fractional quality bound read from an external catalogue and logs a warning when it
   * had to.
   *
   * @param quality the imported value, possibly {@code null}
   * @param field the catalogue field it came from, for the log line
   * @return the value clamped to the range, {@code null} for {@code null}
   */
  @Nullable
  @Contract("null, _ -> null; !null, _ -> !null")
  public static Double clampImported(@Nullable Double quality, @NotNull String field) {
    Double clamped = clamp(quality);
    if (quality != null && !quality.equals(clamped)) {
      log.warn(
          "Imported quality {} in {} lies outside 0..1000; stored as {}", quality, field, clamped);
    }
    return clamped;
  }

  /**
   * Reads a stored quality for floor comparisons; a missing value counts as the lowest quality.
   *
   * @param quality the stored value, possibly {@code null}
   * @return the value, or {@value #MIN} for {@code null}
   */
  public static int orMin(@Nullable Integer quality) {
    return quality == null ? MIN : quality;
  }
}
