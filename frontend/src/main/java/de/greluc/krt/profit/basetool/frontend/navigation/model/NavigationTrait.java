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

/** A rendering trait of a {@link NavigationEntry}. */
public enum NavigationTrait {

  /** Offered only as a quick-access page, never as a menu link. */
  PALETTE_ONLY,

  /** Left out of the quick-access index ({@code data-nav-skip}). */
  NOT_INDEXED,

  /** Leaves the app and opens in a new tab. */
  EXTERNAL,

  /** Shows the caller's unread notification count beside the label. */
  UNREAD_BADGE,

  /** Renders its icon after the label instead of announcing it to the quick access. */
  LABEL_WITH_ICON
}
