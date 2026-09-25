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

package de.greluc.krt.profit.basetool.testsupport.web;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpMethod;

/**
 * One concrete (verb, path) pair to issue, expanded by {@link EndpointEnumeration#mappings}.
 *
 * @param method the HTTP verb
 * @param path the concrete path, every variable substituted
 */
public record Call(@NotNull HttpMethod method, @NotNull String path) {

  /**
   * Renders the call as {@code GET /api/v1/missions}; also the stable sort key of {@link
   * EndpointEnumeration#mappings}.
   *
   * @return the verb and path, separated by a single space
   */
  @Override
  public @NotNull String toString() {
    return method.name() + " " + path;
  }
}
