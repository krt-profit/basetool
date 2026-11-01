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

import org.jetbrains.annotations.NotNull;

/**
 * The tab-bar sections of both modes, each with its own version.
 *
 * @param member the member mode's bar
 * @param admin the administration mode's bar
 */
public record TabBarsResponse(@NotNull TabBarResponse member, @NotNull TabBarResponse admin) {

  /** Both bars automatic. */
  public static final TabBarsResponse AUTOMATIC =
      new TabBarsResponse(TabBarResponse.AUTOMATIC, TabBarResponse.AUTOMATIC);
}
