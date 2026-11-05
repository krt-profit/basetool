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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/** Lenient parsing of catalogue GUID text. */
public final class P4kGuids {

  /** Non-instantiable holder of static helpers. */
  private P4kGuids() {}

  /**
   * Parses GUID text; a blank or malformed value yields {@code null}, so one bad GUID never aborts
   * an import.
   *
   * @param guid the GUID text, or {@code null}
   * @return the UUID, or {@code null}
   */
  @Contract("null -> null")
  @Nullable
  public static UUID parse(@Nullable String guid) {
    if (guid == null || guid.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(guid.trim());
    } catch (IllegalArgumentException _) {
      return null;
    }
  }
}
