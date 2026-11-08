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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Composes the {@code details} of an {@code external_sync_report} event: {@code key=value} entries
 * and bare flags, separated by {@code ;}. A value is stringified with {@link String#valueOf}, so it
 * must hold no user free text or PII.
 */
final class SyncReportDetails {

  /** The entry separator. */
  private static final char SEPARATOR = ';';

  /** The details composed so far. */
  private final StringBuilder buffer = new StringBuilder();

  /** Creates an empty composer; details start with {@link #of}. */
  private SyncReportDetails() {}

  /**
   * Starts the details with their first entry.
   *
   * @param key the key; non-empty, free of {@code =} and {@code ;}
   * @param value the value
   * @return the composer
   */
  @Contract("_, _ -> new")
  @NotNull
  static SyncReportDetails of(@NotNull String key, @Nullable Object value) {
    SyncReportDetails details = new SyncReportDetails();
    details.buffer.append(validKey(key)).append('=').append(value);
    return details;
  }

  /**
   * Appends one entry.
   *
   * @param key the key; non-empty, free of {@code =} and {@code ;}
   * @param value the value
   * @return this composer
   */
  @Contract("_, _ -> this")
  @NotNull
  SyncReportDetails with(@NotNull String key, @Nullable Object value) {
    buffer.append(SEPARATOR).append(validKey(key)).append('=').append(value);
    return this;
  }

  /**
   * Appends a bare flag.
   *
   * @param flag the flag; non-empty, free of {@code =} and {@code ;}
   * @return this composer
   */
  @Contract("_ -> this")
  @NotNull
  SyncReportDetails flag(@NotNull String flag) {
    buffer.append(SEPARATOR).append(validKey(flag));
    return this;
  }

  /**
   * Renders the details.
   *
   * @return the {@code key=value;key=value;flag} text
   */
  @Override
  @NotNull
  public String toString() {
    return buffer.toString();
  }

  /**
   * Rejects a key that would break the {@code key=value;…} grammar.
   *
   * @param key the key
   * @return the key
   * @throws IllegalArgumentException when it is empty or holds {@code =} or {@code ;}
   */
  @NotNull
  private static String validKey(@NotNull String key) {
    if (key.isEmpty() || key.indexOf('=') >= 0 || key.indexOf(SEPARATOR) >= 0) {
      throw new IllegalArgumentException("A sync report detail key cannot be '" + key + "'.");
    }
    return key;
  }
}
