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

package de.greluc.krt.profit.basetool.backend.hangar.api;

/**
 * The JPQL fragment of a bulk ship write that the relocation lock narrows (REQ-PATCH-023), spliced
 * after {@link ShipScope#TRIPLE} into the {@code @Query} values of {@link ShipRepository}.
 */
final class ShipRelocationScope {

  /**
   * Leaves out (alias {@code s}) the ships of the locked owners created before the cutoff or
   * without a creation time; a {@code null} {@code :lockedBefore} leaves out nothing, whatever an
   * empty {@code :lockedOwners} renders to.
   */
  static final String UNLOCKED =
      """
       AND (
         CAST(:lockedBefore AS timestamp) IS NULL
         OR s.owner.id NOT IN :lockedOwners
         OR (s.createdAt IS NOT NULL AND s.createdAt >= :lockedBefore)
       )
      """;

  /** Non-instantiable constant holder. */
  private ShipRelocationScope() {}
}
