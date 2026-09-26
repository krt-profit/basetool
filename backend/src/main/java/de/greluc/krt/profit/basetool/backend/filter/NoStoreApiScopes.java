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

package de.greluc.krt.profit.basetool.backend.filter;

import java.util.List;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The GET families whose response bodies must not be stored anywhere (REQ-SEC-031), shared by
 * {@link ApiCacheControlFilter} and the ETag filter.
 *
 * <p>A sensitive family missing here is downgraded from {@code no-store} to a storable directive,
 * so every sensitive GET family must be added. The Materialbörse is deliberately excluded as an
 * org-wide shared board.
 */
public final class NoStoreApiScopes {

  /** The families themselves, parsed once. */
  private static final List<PathPattern> SCOPES =
      Stream.of(
              "/api/v1/bank/**",
              "/api/v1/org-units/bank/**",
              "/api/v1/users/**",
              "/api/v1/me/**",
              "/api/v1/notifications/**",
              "/api/v1/finance-entries/**",
              "/api/v1/missions/*/finance-entries/**",
              "/api/v1/operations/**",
              "/api/v1/personal-inventory/**",
              "/api/v1/personal-blueprints/**",
              "/api/v1/inventory/**",
              "/api/v1/hangar/**",
              "/api/v1/refinery-orders/**",
              "/api/v1/promotion/**")
          .map(PathPatternParser.defaultInstance::parse)
          .toList();

  /** Not instantiable; the list and the match are all this type carries. */
  private NoStoreApiScopes() {}

  /**
   * Answers whether a request URI names a no-store family, matched against the decoded path
   * (REQ-SEC-029).
   *
   * @param uri the raw request URI; {@code null} answers {@code false}
   * @return {@code true} when the path belongs to a family whose body must not be stored
   */
  public static boolean matches(@Nullable String uri) {
    return uri != null && matches(PathContainer.parsePath(uri));
  }

  /**
   * Answers whether an already-parsed request path names a no-store family.
   *
   * @param path the parsed request path
   * @return {@code true} when the path belongs to a family whose body must not be stored
   */
  public static boolean matches(PathContainer path) {
    for (PathPattern scope : SCOPES) {
      if (scope.matches(path)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The number of families the list carries.
   *
   * <p>Exists so a test can assert the list has not been quietly emptied — a filter that skips
   * nothing and a filter that stores nothing both look green otherwise.
   *
   * @return how many path patterns are frozen here.
   */
  public static int size() {
    return SCOPES.size();
  }
}
