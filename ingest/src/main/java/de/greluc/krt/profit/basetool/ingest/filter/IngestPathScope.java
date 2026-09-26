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

package de.greluc.krt.profit.basetool.ingest.filter;

import jakarta.servlet.http.HttpServletRequest;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Decides whether a request targets the ingest endpoints ({@code /v1/**}), shared by every
 * ingest-scoped filter.
 *
 * <p>Matches the decoded path, as the dispatcher does, so a percent-encoded path cannot bypass the
 * filters while still reaching the ingest controller.
 */
final class IngestPathScope {

  /** The parsed {@code /v1/**} pattern of the ingest surface. */
  private static final PathPattern INGEST_PATHS = PathPatternParser.defaultInstance.parse("/v1/**");

  /** Not instantiable: this is a single shared predicate, not a collaborator. */
  private IngestPathScope() {}

  /**
   * Whether the request targets an ingest endpoint, decided on the decoded path without
   * context-path stripping.
   *
   * @param request the current request
   * @return {@code true} when the decoded path is under {@code /v1}
   */
  static boolean isIngestRequest(@NotNull HttpServletRequest request) {
    return INGEST_PATHS.matches(PathContainer.parsePath(request.getRequestURI()));
  }
}
