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

package de.greluc.krt.profit.basetool.frontend.promotion.model;

import org.jetbrains.annotations.NotNull;

/**
 * Whose promotion catalogue a backend read returns: the caller's own squadron, as members see it,
 * or the squadrons the caller manages as an officer. Mirrors the backend {@code PromotionReadView}.
 */
public enum PromotionReadView {

  /** The caller's active squadron; the member-facing catalogue and progress. */
  MEMBER,

  /** The squadrons within the caller's officer reach; the management pages. */
  MANAGEMENT;

  /**
   * The {@code view} query parameter selecting this view, for appending to a backend path; the
   * member view is the backend default and adds nothing.
   *
   * @param separator {@code '?'} when the path has no query yet, {@code '&'} otherwise
   * @return the parameter with its separator, or an empty string for the member view
   */
  @NotNull
  public String query(char separator) {
    return this == MEMBER ? "" : separator + "view=" + name();
  }
}
