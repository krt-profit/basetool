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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

/**
 * The JPQL rule of the materials the lists and pickers offer (REQ-P4K-013, REQ-P4K-075). The
 * fragment is a compile-time constant, so a {@code @Query} concatenates it; its query must alias
 * the material {@code m}.
 */
public final class SelectableMaterialJpql {

  /**
   * The material aliased {@code m} is visible, neither swept nor hidden through a review case, and
   * not hidden as a commodity no game source knows ({@link Material#isSourcelessHidden()}).
   */
  public static final String SELECTABLE_MATERIAL =
      " m.isVisible = true AND m.p4kDeletedAt IS NULL AND m.p4kHiddenAt IS NULL"
          + " AND (m.sourcelessHiddenAt IS NULL OR m.p4kUuid IS NOT NULL"
          + " OR m.scwikiUuid IS NOT NULL OR m.p4kSubtypeUuid IS NOT NULL"
          + " OR m.priceBuy > 0 OR m.priceSell > 0) ";

  /** Non-instantiable constant holder. */
  private SelectableMaterialJpql() {}
}
