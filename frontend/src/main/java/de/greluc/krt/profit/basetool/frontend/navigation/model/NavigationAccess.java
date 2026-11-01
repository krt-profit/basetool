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

import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * What a caller brings to the navigation gates of one request.
 *
 * @param roles the bare role codes the caller reaches through the role hierarchy
 * @param flags the per-request flags that are set
 */
public record NavigationAccess(@NotNull Set<String> roles, @NotNull Set<NavigationFlag> flags) {

  /**
   * Copies the sets so the access stays immutable.
   *
   * @param roles the reachable bare role codes
   * @param flags the set flags
   */
  public NavigationAccess {
    roles = Set.copyOf(roles);
    flags = Set.copyOf(flags);
  }
}
