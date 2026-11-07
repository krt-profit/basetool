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

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A game version as major.minor.patch, compared numerically per component (REQ-OBS-022).
 *
 * @param major the major version
 * @param minor the minor version
 * @param patch the patch version, {@code 0} when the text names none
 */
public record P4kGameVersion(int major, int minor, int patch)
    implements Comparable<P4kGameVersion> {

  /** The leading version of a text: {@code 4.10.2-LIVE.12881860} reads as {@code 4.10.2}. */
  private static final Pattern LEADING_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

  private static final Comparator<P4kGameVersion> ORDER =
      Comparator.comparingInt(P4kGameVersion::major)
          .thenComparingInt(P4kGameVersion::minor)
          .thenComparingInt(P4kGameVersion::patch);

  /**
   * Reads the leading major.minor[.patch] of a text.
   *
   * @param text a version text such as {@code 4.10.2}, {@code 4.10} or {@code 4.10.2-LIVE.1}
   * @return the version, or {@code null} when the text starts with none
   */
  @Nullable
  public static P4kGameVersion parse(@Nullable String text) {
    if (text == null) {
      return null;
    }
    Matcher matcher = LEADING_VERSION.matcher(text.strip());
    if (!matcher.find()) {
      return null;
    }
    try {
      return new P4kGameVersion(
          Integer.parseInt(matcher.group(1)),
          Integer.parseInt(matcher.group(2)),
          matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)));
    } catch (NumberFormatException _) {
      return null;
    }
  }

  /**
   * Returns the highest version among texts, skipping those that hold none.
   *
   * @param texts the version texts
   * @return the highest version, or {@code null} when no text holds one
   */
  @Nullable
  public static P4kGameVersion highest(@NotNull Collection<String> texts) {
    return texts.stream()
        .map(P4kGameVersion::parse)
        .filter(Objects::nonNull)
        .max(ORDER)
        .orElse(null);
  }

  @Override
  public int compareTo(@NotNull P4kGameVersion other) {
    return ORDER.compare(this, other);
  }

  /**
   * Prints the version as {@code major.minor.patch}.
   *
   * @return e.g. {@code 4.10.2}
   */
  @Override
  @NotNull
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}
