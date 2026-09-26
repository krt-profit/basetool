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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fluent composer for the space-separated {@code key=value} {@code details} payload passed to
 * {@code AuditService.record(...)} / {@code BankAuditService.record(...)}.
 *
 * <p>Implements {@link CharSequence} so it can be passed directly. Values are stringified with
 * {@link String#valueOf(Object)}, so the output is identical to {@code "a=" + x + " b=" + y}. Only
 * keys are validated; value content is not, and must contain no user free text or PII
 * (REQ-AUDIT-001).
 */
public final class AuditDetails implements CharSequence {

  /** Accumulates the {@code key=value key=value ...} payload as it is composed. */
  private final StringBuilder buffer = new StringBuilder();

  /** Creates an empty composer; a payload is started through {@link #of(String, Object)}. */
  private AuditDetails() {}

  /**
   * Starts a detail payload with its first {@code key=value} pair.
   *
   * @param key the first key; must be non-empty and free of {@code '='} and whitespace
   * @param value the first value; stringified via {@link String#valueOf(Object)} (a {@code null}
   *     renders as the literal {@code "null"}), byte-identical to {@code "key=" + value}
   * @return a new builder holding {@code "key=value"}
   */
  @Contract("_, _ -> new")
  public static @NotNull AuditDetails of(@NotNull String key, @Nullable Object value) {
    AuditDetails details = new AuditDetails();
    details.append(key, value);
    return details;
  }

  /**
   * Appends {@code " key=value"} (a single leading space separator) to the payload.
   *
   * @param key the next key; must be non-empty and free of {@code '='} and whitespace
   * @param value the next value; stringified via {@link String#valueOf(Object)} (a {@code null}
   *     renders as the literal {@code "null"})
   * @return this builder, for chaining
   */
  @Contract("_, _ -> this")
  public @NotNull AuditDetails with(@NotNull String key, @Nullable Object value) {
    buffer.append(' ');
    append(key, value);
    return this;
  }

  /**
   * Appends {@code key=String.valueOf(value)} to the buffer after validating the key.
   *
   * @param key the key to validate and append
   * @param value the value, stringified via {@link String#valueOf(Object)}
   */
  private void append(@NotNull String key, @Nullable Object value) {
    validateKey(key);
    buffer.append(key).append('=').append(String.valueOf(value));
  }

  /**
   * Rejects a key that would corrupt the {@code key=value} grammar — a {@code null}/empty key, or
   * one containing {@code '='} (ambiguous split) or whitespace (would fuse with the space
   * separator).
   *
   * @param key the key to validate
   * @throws IllegalArgumentException if {@code key} is {@code null}, empty, or contains {@code '='}
   *     or whitespace
   */
  private static void validateKey(@Nullable String key) {
    if (key == null || key.isEmpty()) {
      throw new IllegalArgumentException("Audit detail key must be non-empty");
    }
    for (int i = 0; i < key.length(); i++) {
      char c = key.charAt(i);
      if (c == '=' || Character.isWhitespace(c)) {
        throw new IllegalArgumentException(
            "Audit detail key must not contain '=' or whitespace: '" + key + "'");
      }
    }
  }

  /**
   * The number of characters in the composed payload — the {@link CharSequence} contract, delegated
   * to the backing buffer.
   *
   * @return the current payload length
   */
  @Override
  public int length() {
    return buffer.length();
  }

  /**
   * Returns the character at the given index of the composed payload.
   *
   * @param index the zero-based character index
   * @return the character at {@code index}
   * @throws IndexOutOfBoundsException if {@code index} is negative or not less than {@link
   *     #length()}
   */
  @Override
  public char charAt(int index) {
    return buffer.charAt(index);
  }

  /**
   * Returns a subsequence of the composed payload.
   *
   * @param start the start index, inclusive
   * @param end the end index, exclusive
   * @return the requested subsequence
   * @throws IndexOutOfBoundsException if {@code start}/{@code end} are out of range or {@code start
   *     > end}
   */
  @Override
  public @NotNull CharSequence subSequence(int start, int end) {
    return buffer.subSequence(start, end);
  }

  /**
   * Renders the composed {@code key=value key=value ...} payload — also how {@code record(...)}
   * turns this {@link CharSequence} into the persisted {@code details} string.
   *
   * @return the detail string, byte-identical to the equivalent {@code "k=" + v + " k2=" + v2}
   *     concatenation
   */
  @Override
  public @NotNull String toString() {
    return buffer.toString();
  }
}
