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

package de.greluc.krt.profit.basetool.frontend.model;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where an org unit sits in the hierarchy, as the Leitung unit tree shows it: the Bereich above a
 * Staffel or Spezialkommando and the department colour it inherits (REQ-ROLE-004).
 *
 * @param bereichName the name of the parent Bereich, or {@code null} for a Bereich itself and for a
 *     unit without one.
 * @param department the department enum name of the unit's Bereich (its own for a Bereich), or
 *     {@code null} when none is assigned.
 */
public record LeitungUnitContext(@Nullable String bereichName, @Nullable String department) {

  /**
   * The department as a CSS modifier, lower case with hyphens ({@code SEARCH_RESCUE} becomes {@code
   * search-rescue}).
   *
   * @return the modifier, or {@code "none"} when no department is assigned.
   */
  @NotNull
  public String departmentModifier() {
    if (department == null || department.isBlank()) {
      return "none";
    }
    return department.toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
