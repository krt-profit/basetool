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

package de.greluc.krt.profit.basetool.frontend.kernel.layout;

import org.jetbrains.annotations.NotNull;
import org.springframework.web.util.UriComponentsBuilder;

/** Builds the page links of quick-access hits. */
public final class QuickAccessHrefs {

  private QuickAccessHrefs() {}

  /**
   * A link to a list page filtered by a name.
   *
   * @param path the page path, starting with {@code /}
   * @param name the entity name the page filters on
   * @return the path with the name as an encoded {@code search} parameter
   */
  public static @NotNull String filteredBy(@NotNull String path, @NotNull String name) {
    return UriComponentsBuilder.fromPath(path)
        .queryParam("search", "{name}")
        .encode()
        .buildAndExpand(name)
        .toUriString();
  }
}
