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

import java.util.Locale;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * The one folding of the class names and names a P4K run matches on (REQ-P4K-007): surrounding
 * blanks dropped and upper-cased like {@code upper(...)} in the queries, so the row index, the
 * repeated-key scan and the UEX alias lookup compare the same text.
 */
final class P4kMatchKeys {

  /** Non-instantiable holder of the folding rule. */
  private P4kMatchKeys() {}

  /**
   * Folds a match key.
   *
   * @param key the class name or name, or {@code null}
   * @return the stripped, upper-cased key, or {@code null}
   */
  @Contract("null -> null; !null -> !null")
  @Nullable
  static String fold(@Nullable String key) {
    return key == null ? null : key.strip().toUpperCase(Locale.ROOT);
  }
}
