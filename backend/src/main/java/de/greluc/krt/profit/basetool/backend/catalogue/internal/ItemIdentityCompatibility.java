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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.StringUtils;

/** Refuses exact-name reconciliation when catalogue facts identify incompatible item variants. */
public final class ItemIdentityCompatibility {

  private ItemIdentityCompatibility() {}

  /**
   * Verifies that the known kind, manufacturer and engine class do not contradict the source.
   *
   * @param candidate existing item
   * @param kind source item kind
   * @param manufacturer resolved source manufacturer
   * @param className source engine class
   * @return whether every known discriminating fact agrees
   */
  static boolean matches(
      @NotNull GameItem candidate,
      @NotNull GameItemKind kind,
      @Nullable Manufacturer manufacturer,
      @Nullable String className) {
    if (candidate.getKind() != GameItemKind.GENERIC
        && kind != GameItemKind.GENERIC
        && candidate.getKind() != kind) {
      return false;
    }
    if (candidate.getManufacturer() != null
        && manufacturer != null
        && !Objects.equals(candidate.getManufacturer().getId(), manufacturer.getId())) {
      return false;
    }
    return !StringUtils.hasText(candidate.getClassName())
        || !StringUtils.hasText(className)
        || candidate.getClassName().equalsIgnoreCase(className);
  }
}
