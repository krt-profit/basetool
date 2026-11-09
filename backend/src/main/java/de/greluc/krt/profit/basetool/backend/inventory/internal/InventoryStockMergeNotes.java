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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Preserves distinct stock notes in encounter order within the persistent column limit. */
public final class InventoryStockMergeNotes {

  private static final int MAX_LENGTH = 1000;
  private static final String SEPARATOR = "\n";

  private InventoryStockMergeNotes() {}

  /**
   * Adds a trimmed nonblank note while retaining first-seen order.
   *
   * @param notes the ordered distinct-note accumulator
   * @param note the raw stock note
   */
  static void collect(@NotNull Set<String> notes, @Nullable String note) {
    String normalized = StringNormalization.trimToNull(note);
    if (normalized != null) {
      notes.add(normalized);
    }
  }

  /**
   * Joins distinct notes within the persistent stock-note length.
   *
   * @param notes the ordered distinct notes
   * @return the combined note, or null when the rows carried no note
   */
  static @Nullable String combined(@NotNull Set<String> notes) {
    if (notes.isEmpty()) {
      return null;
    }
    String joined = String.join(SEPARATOR, notes);
    return joined.length() > MAX_LENGTH ? joined.substring(0, MAX_LENGTH) : joined;
  }
}
