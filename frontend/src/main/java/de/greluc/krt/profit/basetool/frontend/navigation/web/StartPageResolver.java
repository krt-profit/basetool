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

package de.greluc.krt.profit.basetool.frontend.navigation.web;

import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAccess;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAppearance;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationAvailability;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationEntry;
import de.greluc.krt.profit.basetool.frontend.navigation.model.NavigationMode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Resolves the stored start page of a mode to the path it opens (REQ-UI-034): the path of an entry
 * of that mode the caller may open here and now, never anything the member typed.
 */
@Component
@RequiredArgsConstructor
public class StartPageResolver {

  private final NavigationRegistry registry;

  /**
   * Resolves the start page of a mode.
   *
   * @param mode the mode being entered
   * @param appearance the caller's stored appearance
   * @param access the caller's reachable roles and flags
   * @return the registry path of the start page, or empty when the mode has none or the caller may
   *     not open it in the active context
   */
  @NotNull
  public Optional<String> resolve(
      @NotNull NavigationMode mode,
      @NotNull NavigationAppearance appearance,
      @NotNull NavigationAccess access) {
    String key = appearance.startPage(mode);
    if (key == null || registry.isAdminEntry(key) != (mode == NavigationMode.ADMIN)) {
      return Optional.empty();
    }
    return registry
        .starrable(key)
        .filter(entry -> registry.availability(entry, access) == NavigationAvailability.AVAILABLE)
        .map(NavigationEntry::href)
        .filter(href -> href.startsWith("/"));
  }
}
