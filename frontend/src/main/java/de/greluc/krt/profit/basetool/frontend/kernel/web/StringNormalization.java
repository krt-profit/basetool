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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/** Application binding facade for the shared inbound-string normalization contract. */
public final class StringNormalization {

  /** The free-text length cap, matching the backend's {@code MAX_FREE_TEXT_LENGTH}. */
  public static final int MAX_FREE_TEXT_LENGTH =
      de.greluc.krt.profit.basetool.technical.StringNormalization.MAX_FREE_TEXT_LENGTH;

  /** Non-instantiable holder of static normalization helpers. */
  private StringNormalization() {}

  /**
   * NFC-normalizes {@code value} and enforces {@code maxLength}; trimming and null handling are the
   * caller's.
   *
   * @param value the trimmed, non-null value to canonicalize
   * @param maxLength the inclusive maximum length after normalization
   * @return the NFC-normalized value
   * @throws IllegalArgumentException when the normalized value exceeds {@code maxLength}
   */
  public static String normalizeAndCap(String value, int maxLength) {
    return de.greluc.krt.profit.basetool.technical.StringNormalization.normalizeAndCap(
        value, maxLength);
  }

  /**
   * Maps a {@code null} or {@link String#isBlank() blank} string to {@code null}, otherwise returns
   * it unchanged (not stripped).
   *
   * @param value the candidate value, may be {@code null}
   * @return {@code null} when {@code value} is {@code null} or blank, otherwise {@code value}
   */
  @Contract(value = "null -> null", pure = true)
  public static @Nullable String blankToNull(@Nullable String value) {
    return de.greluc.krt.profit.basetool.technical.StringNormalization.blankToNull(value);
  }

  /**
   * Strips surrounding whitespace with {@link String#strip()} and maps a {@code null} or blank
   * result to {@code null}.
   *
   * @param value the candidate value, may be {@code null}
   * @return the stripped value, or {@code null} when {@code value} is {@code null} or blank
   */
  @Contract(value = "null -> null", pure = true)
  public static @Nullable String trimToNull(@Nullable String value) {
    return de.greluc.krt.profit.basetool.technical.StringNormalization.trimToNull(value);
  }

  /**
   * Full inbound-string pipeline: {@link String#trim()}, optionally empty-to-{@code null}, then
   * {@link #normalizeAndCap(String, int)}; {@code null} stays {@code null}.
   *
   * @param value the raw inbound value, may be {@code null}
   * @param maxLength the inclusive maximum length after normalization
   * @param emptyAsNull whether a trimmed-empty value becomes {@code null}
   * @return the trimmed, normalized, length-checked value, or {@code null}
   * @throws IllegalArgumentException when the normalized value exceeds {@code maxLength}
   */
  @Contract(value = "null, _, _ -> null", pure = true)
  public static @Nullable String normalize(
      @Nullable String value, int maxLength, boolean emptyAsNull) {
    return de.greluc.krt.profit.basetool.technical.StringNormalization.normalize(
        value, maxLength, emptyAsNull);
  }
}
