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
 * @param rightsKnown whether the flags come from a capability answer; {@code false} after a failed
 *     read, when an unset flag says nothing about a lost right
 */
public record NavigationAccess(
    @NotNull Set<String> roles, @NotNull Set<NavigationFlag> flags, boolean rightsKnown) {

  /**
   * Copies the sets so the access stays immutable.
   *
   * @param roles the reachable bare role codes
   * @param flags the set flags
   * @param rightsKnown whether the flags come from a capability answer
   */
  public NavigationAccess {
    roles = Set.copyOf(roles);
    flags = Set.copyOf(flags);
  }

  /**
   * An access whose flags come from a capability answer.
   *
   * @param roles the reachable bare role codes
   * @param flags the set flags
   */
  public NavigationAccess(@NotNull Set<String> roles, @NotNull Set<NavigationFlag> flags) {
    this(roles, flags, true);
  }
}
