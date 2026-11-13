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
import org.jetbrains.annotations.Nullable;

/** Classifies manufacturer records whose path describes a paint logo or shop. */
public final class P4kManufacturerRecordPath {

  private static final String PAINT_LOGO = "/scitemmanufacturer/paintcolorlogos/";
  private static final String SHOP = "/scitemmanufacturer/shop/";

  private P4kManufacturerRecordPath() {}

  /**
   * Returns the source family used to group manufacturer paint logo findings.
   *
   * @param path the catalogue record path
   * @return the paint-logo family, or null for other records
   */
  @Nullable
  static String paintFamily(@Nullable String path) {
    return path != null && normalize(path).contains(PAINT_LOGO) ? PAINT_LOGO : null;
  }

  /**
   * Tests whether the source path identifies an auxiliary record that can carry a brand label.
   *
   * @param path the catalogue record path
   * @return whether the path identifies a paint logo or shop
   */
  static boolean isAuxiliary(@Nullable String path) {
    if (path == null) {
      return false;
    }
    String normalized = normalize(path);
    return normalized.contains(PAINT_LOGO) || normalized.contains(SHOP);
  }

  private static String normalize(String path) {
    return path.replace('\\', '/').toLowerCase(Locale.ROOT);
  }
}
