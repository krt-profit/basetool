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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kItemDto;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The share of a catalogue's items without an {@code itemClass} (REQ-OBS-022). */
final class P4kItemClassShare {

  /** Non-instantiable holder of a static computation. */
  private P4kItemClassShare() {}

  /**
   * Computes the share of items whose {@code itemClass} is {@code null} or blank; an unknown value
   * is a value, not a {@code null}.
   *
   * @param items the catalogue's items
   * @return the share from 0 to 1, or {@code null} when there is no item
   */
  @Nullable
  static Double of(@NotNull List<P4kItemDto> items) {
    if (items.isEmpty()) {
      return null;
    }
    long withoutClass =
        items.stream().filter(i -> i.itemClass() == null || i.itemClass().isBlank()).count();
    return (double) withoutClass / items.size();
  }
}
