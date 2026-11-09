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

import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/** Reads the Basetool ids a client sends or the journal stores. */
final class ExchangeIds {

  private ExchangeIds() {}

  /**
   * Reads an id.
   *
   * @param raw the id as sent or stored, or {@code null}
   * @return the id, or {@code null} when the text is absent or no Basetool id
   */
  @Contract("null -> null")
  static @Nullable UUID parse(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException _) {
      return null;
    }
  }
}
