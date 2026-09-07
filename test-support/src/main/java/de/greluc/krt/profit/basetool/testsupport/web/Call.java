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

import org.springframework.http.HttpMethod;

/**
 * One (verb, path) pair to issue, expanded from a {@code RequestMappingInfo} by {@link
 * EndpointEnumeration#mappings}.
 *
 * <p>The path is concrete: every {@code {variable}} has already been substituted, so the value can
 * be handed to {@code MockMvc} as-is. A pattern that has no single concrete spelling never becomes
 * a {@code Call} at all.
 *
 * @param method the HTTP verb
 * @param path the concrete path, with every variable substituted
 */
public record Call(HttpMethod method, String path) {

  /**
   * Renders the call the way an assertion message should read: {@code GET /api/v1/missions}.
   *
   * <p>This is also the sort key {@link EndpointEnumeration#mappings} orders by, so a sweep's
   * failure list comes out in the same order on every run and two runs can be diffed against each
   * other.
   *
   * @return the verb and path, separated by a single space
   */
  @Override
  public String toString() {
    return method.name() + " " + path;
  }
}
