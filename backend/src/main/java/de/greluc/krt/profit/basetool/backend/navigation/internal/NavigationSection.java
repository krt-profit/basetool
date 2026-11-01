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

package de.greluc.krt.profit.basetool.backend.navigation.internal;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** A preference section with its own version counter; its tag bounds the metric label. */
@Getter
@RequiredArgsConstructor
public enum NavigationSection {

  /** The favourites (REQ-UI-032). */
  FAVOURITES("favourites"),

  /** The hidden pages (REQ-UI-032). */
  HIDDEN("hidden"),

  /** The group and link order of both modes (REQ-UI-032). */
  ORDER("order");

  private final String tag;
}
