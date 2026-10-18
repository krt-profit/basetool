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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Reads the fields of a journaled state for the typed states of the resource journals. */
final class ExchangeJournalJson {

  /** Not instantiable. */
  private ExchangeJournalJson() {}

  /**
   * Reads a text field.
   *
   * @param node the state, or {@code null}
   * @param field the field
   * @return its text, or {@code null} when absent or null
   */
  static @Nullable String text(@Nullable JsonNode node, @NotNull String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  /**
   * Reads a UUID field.
   *
   * @param node the state, or {@code null}
   * @param field the field
   * @return its UUID, or {@code null} when absent, null or no UUID
   */
  static @Nullable UUID uuid(@Nullable JsonNode node, @NotNull String field) {
    String raw = text(node, field);
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException _) {
      return null;
    }
  }

  /**
   * Reads a number field.
   *
   * @param node the state, or {@code null}
   * @param field the field
   * @return its value, or {@code null} when absent or no number
   */
  static @Nullable BigDecimal decimal(@Nullable JsonNode node, @NotNull String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(field);
    return value == null || !value.isNumber() ? null : value.decimalValue();
  }
}
