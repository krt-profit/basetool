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

package de.greluc.krt.profit.basetool.backend.support;

import java.text.Normalizer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * Shared normalization primitives for inbound free-text fields, used by both the JSON and the
 * form-binding path so identical text reaches the database identically: the length cap, NFC
 * normalization, the full {@link #normalize(String, int, boolean) pipeline} and the {@link
 * #blankToNull(String)} / {@link #trimToNull(String)} collapses.
 */
public final class StringNormalization {

  /**
   * The single free-text length cap, matching the longest free-text column in the schema. Shared by
   * the JSON deserializer and the form editor so the limit is declared in exactly one place instead
   * of being repeated as a bare {@code 8000} literal at each site.
   */
  public static final int MAX_FREE_TEXT_LENGTH = 8000;

  /** Non-instantiable holder of static normalization helpers. */
  private StringNormalization() {}

  /**
   * NFC-normalizes an already-trimmed, non-null {@code value} and enforces {@code maxLength}.
   *
   * @param value the already-trimmed, non-null value to canonicalize
   * @param maxLength the inclusive maximum allowed length after normalization
   * @return the NFC-normalized value
   * @throws IllegalArgumentException when the normalized value exceeds {@code maxLength} (HTTP 400)
   */
  public static String normalizeAndCap(String value, int maxLength) {
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException("String exceeds maximum allowed length of " + maxLength);
    }
    return normalized;
  }

  /**
   * Collapses a {@code null} or {@link String#isBlank() blank} string to {@code null}, otherwise
   * returns it unstripped.
   *
   * @param value the candidate value, may be {@code null}
   * @return {@code null} when {@code value} is {@code null} or blank, otherwise {@code value}
   *     unchanged
   */
  @Contract(value = "null -> null", pure = true)
  public static @Nullable String blankToNull(@Nullable String value) {
    return (value == null || value.isBlank()) ? null : value;
  }

  /**
   * Strips leading and trailing Unicode whitespace and collapses a {@code null} or blank result to
   * {@code null}.
   *
   * @param value the candidate value, may be {@code null}
   * @return the stripped value, or {@code null} when {@code value} is {@code null} or blank
   */
  @Contract(value = "null -> null", pure = true)
  public static @Nullable String trimToNull(@Nullable String value) {
    return (value == null || value.isBlank()) ? null : value.strip();
  }

  /**
   * Full inbound-string pipeline: {@link String#trim()}, optionally ASCII-empty to {@code null},
   * then {@link #normalizeAndCap(String, int)}. A {@code null} input stays {@code null}.
   *
   * @param value the raw inbound value, may be {@code null}
   * @param maxLength the inclusive maximum length enforced after normalization
   * @param emptyAsNull whether a trimmed-empty value collapses to {@code null}
   * @return the trimmed, NFC-normalized, length-checked value, or {@code null}
   * @throws IllegalArgumentException when the normalized value exceeds {@code maxLength} (HTTP 400)
   */
  @Contract(value = "null, _, _ -> null", pure = true)
  public static @Nullable String normalize(
      @Nullable String value, int maxLength, boolean emptyAsNull) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (emptyAsNull && trimmed.isEmpty()) {
      return null;
    }
    return normalizeAndCap(trimmed, maxLength);
  }
}
