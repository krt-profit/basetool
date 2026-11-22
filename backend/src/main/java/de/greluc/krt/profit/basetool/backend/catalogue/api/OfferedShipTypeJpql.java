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
 * The JPQL form of {@link OfferedShipTypes#isOffered(ShipType)} (REQ-P4K-080). The fragment is a
 * compile-time constant, so a {@code @Query} concatenates it; it names the alias {@code s}.
 */
public final class OfferedShipTypeJpql {

  /** The ship type aliased {@code s} is listed by the P4K and neither hidden nor retired. */
  public static final String OFFERED_SHIP =
      " s.hidden = false AND s.p4kUuid IS NOT NULL AND s.p4kDeletedAt IS NULL"
          + " AND s.p4kHiddenAt IS NULL ";

  /** Non-instantiable constant holder. */
  private OfferedShipTypeJpql() {}
}
