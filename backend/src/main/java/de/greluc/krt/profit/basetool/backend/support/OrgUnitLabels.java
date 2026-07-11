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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * Shared renderer for the compact, non-personal audit label of an org unit, so the membership, role
 * and Kommando-group audit paths emit the same {@code subjectLabel} instead of each carrying a
 * private copy.
 */
public final class OrgUnitLabels {

  private OrgUnitLabels() {}

  /**
   * Resolves the compact audit label for an org unit — its shorthand, falling back to its name —
   * used as the {@code subjectLabel} on role / membership audit events. Null-tolerant: a {@code
   * null} unit yields {@code null} (so id-lookup paths can pass an unresolved unit straight
   * through), and a unit whose shorthand is blank and whose name is {@code null} likewise yields
   * {@code null}.
   *
   * @param unit the org unit, or {@code null}
   * @return the shorthand if set and non-blank, otherwise the name, otherwise {@code null}
   */
  @Contract("null -> null")
  public static @Nullable String shorthandOrName(@Nullable OrgUnit unit) {
    if (unit == null) {
      return null;
    }
    String shorthand = unit.getShorthand();
    return shorthand != null && !shorthand.isBlank() ? shorthand : unit.getName();
  }
}
