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

package de.greluc.krt.profit.basetool.frontend.navigation.model;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * One default slot of the phone tab bar: the first permitted of its candidate entries opens from
 * it, and the area it marks as current is the first candidate's path.
 *
 * @param entryKeys the candidate entry keys, the primary entry first
 */
public record NavigationTab(@NotNull List<String> entryKeys) {

  /**
   * Refuses a slot without candidates and copies the keys.
   *
   * @param entryKeys the candidate entry keys
   * @throws IllegalArgumentException if no candidate is given
   */
  public NavigationTab {
    if (entryKeys.isEmpty()) {
      throw new IllegalArgumentException("A tab needs at least one entry key");
    }
    entryKeys = List.copyOf(entryKeys);
  }
}
