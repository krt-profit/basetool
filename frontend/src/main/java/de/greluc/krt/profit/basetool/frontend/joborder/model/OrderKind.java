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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;

/** The implemented order kinds offered by the shared workspace filter. */
@RequiredArgsConstructor
public enum OrderKind {
  /** Raw-material procurement. */
  MATERIAL("orders.kind.material"),
  /** Finished-item production. */
  ITEM("orders.kind.item");

  private final String labelKey;

  /**
   * Returns the translated kind label's message key.
   *
   * @return the existing kind label key
   */
  public String labelKey() {
    return labelKey;
  }

  /**
   * Resolves an implemented kind from its wire name.
   *
   * @param value the optional filter value
   * @return the kind, absent for an unknown value
   */
  public static @Nullable OrderKind parseOrNull(@Nullable String value) {
    return Arrays.stream(values())
        .filter(kind -> kind.name().equals(value))
        .findFirst()
        .orElse(null);
  }
}
