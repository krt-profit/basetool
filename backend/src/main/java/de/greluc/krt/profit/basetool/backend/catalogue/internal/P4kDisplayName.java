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

/** Tells a real P4K catalogue display name from an unresolved key or a placeholder token. */
public final class P4kDisplayName {

  private P4kDisplayName() {}

  /**
   * Whether the name is a real, player-facing display name: present, not an unresolved {@code @LOC}
   * key, and not a placeholder token. This is the primary filter against seeding internal records.
   *
   * @param name the candidate display name
   * @return {@code true} when the name looks like real content
   */
  @Contract("null -> false")
  static boolean isReal(@Nullable String name) {
    if (name == null || name.isBlank()) {
      return false;
    }
    String trimmed = name.trim();
    if (trimmed.startsWith("@")) {
      return false;
    }
    String lower = trimmed.toLowerCase(Locale.ROOT);
    return !lower.equals("uninitialized")
        && !lower.equals("empty")
        && !lower.startsWith("loc_placeholder")
        && !lower.startsWith("loc_empty");
  }
}
