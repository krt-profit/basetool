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
 * The JPQL form of {@link GameItem#isSelectable()}, for the item pickers that offer only what a new
 * reference may take (REQ-P4K-013, REQ-P4K-048). The fragment is a compile-time constant, so a
 * {@code @Query} concatenates it; its query must alias the game item {@code gi}.
 */
public final class SelectableGameItemJpql {

  /** The game item aliased {@code gi} is listed by the P4K and not hidden as unobtainable. */
  public static final String SELECTABLE_ITEM =
      P4kRetirementJpql.LISTED_ITEM + "AND gi.unobtainableSince IS NULL ";

  /** Non-instantiable constant holder. */
  private SelectableGameItemJpql() {}
}
