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

package de.greluc.krt.profit.basetool.ingest.edge;

import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import jakarta.servlet.http.HttpServletRequest;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Decides whether a request targets the exchange ({@code /exchange/**}), the gateway's only
 * protected surface, shared by every scoped filter (REQ-XCH-001).
 *
 * <p>Matches the decoded path, as the dispatcher does, so a percent-encoded path cannot bypass the
 * filters while still reaching a controller.
 */
public final class IngestPathScope {

  /** The parsed {@code /exchange/**} pattern of the exchange surface. */
  private static final PathPattern EXCHANGE_PATHS =
      PathPatternParser.defaultInstance.parse("/exchange/**");

  /** Not instantiable: this is a single shared predicate, not a collaborator. */
  private IngestPathScope() {}

  /**
   * Returns the metric label of the surface the request targets.
   *
   * @param request the current request
   * @return {@code exchange} or {@code other}
   */
  public static @NotNull String scopeLabel(@NotNull HttpServletRequest request) {
    return isExchangeRequest(request)
        ? MetricNames.PATH_SCOPE_EXCHANGE
        : MetricNames.PATH_SCOPE_OTHER;
  }

  /**
   * Whether the request targets the exchange surface — the per-IP limit, the payload cap and the
   * access log apply.
   *
   * @param request the current request
   * @return {@code true} when the path is under {@code /exchange}
   */
  public static boolean isExchangeRequest(@NotNull HttpServletRequest request) {
    return EXCHANGE_PATHS.matches(PathContainer.parsePath(request.getRequestURI()));
  }
}
