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
 * The one answer to "is this request one of the ingest endpoints?", shared by every filter that
 * limits itself to {@code /v1/**} — client identity, payload cap, rate limit and the access log.
 *
 * <p>Exists as a single seam because these four are protective scopes: each one is <em>skipped</em>
 * for a non-matching path, so a filter that disagrees with the others about what {@code /v1} means
 * silently stops protecting an endpoint the rest still guard. Sharing the pattern makes that
 * divergence impossible.
 *
 * <p><b>Why a parsed {@link PathPattern} and not {@code requestUri.startsWith("/v1/")}.</b> {@link
 * HttpServletRequest#getRequestURI()} is the <em>raw</em> percent-encoded URI per the servlet spec,
 * while Spring MVC routes on the <em>decoded</em> path. {@code POST /%761/refinery-extract}
 * therefore fails a raw prefix test — every one of those filters skips it — and {@code
 * RequestMappingHandlerMapping} then decodes {@code %761} to {@code v1} and dispatches it to the
 * ingest controller anyway: the client-identity allowlist (REQ-INGEST-011), the memory-DoS payload
 * cap and the rate limit are all bypassed by encoding one character, and no access-log line records
 * that it happened. The default {@code StrictHttpFirewall} blocks {@code %2e}, {@code %2f}, {@code
 * %25} and friends, but not ordinary letter escapes like {@code %76}.
 *
 * <p>{@link PathPattern} matches on {@code PathSegment#valueToMatch()}, which is decoded, so the
 * filters and the dispatcher agree on the same path. Note that {@code ServletRequestPathUtils} does
 * <em>not</em> fix this: {@code PathContainer.Element#value()} is contractually the unmodified
 * original.
 *
 * <p>Authentication itself was never affected — the resource-server chain's {@code
 * anyRequest().authenticated()} is evaluated by Spring Security's own decoded matchers, so an
 * encoded path still needs a valid realm token. What it bypassed is everything layered on top of
 * that token.
 */
final class IngestPathScope {

  /**
   * The ingest surface. Parsed once; {@link PathPattern#matches} is allocation-light per request
   * beyond the {@link PathContainer} the caller's path is parsed into.
   */
  private static final PathPattern INGEST_PATHS = PathPatternParser.defaultInstance.parse("/v1/**");

  /** Not instantiable: this is a single shared predicate, not a collaborator. */
  private IngestPathScope() {}

  /**
   * Whether the request targets an ingest endpoint, decided on the decoded path.
   *
   * <p>The URI is matched whole (no context-path stripping), mirroring the raw prefix test this
   * replaced — the gateway is deployed at the container root and defines no servlet context path.
   *
   * @param request the current request
   * @return {@code true} when the decoded path is under {@code /v1}, i.e. when the ingest-scoped
   *     filters must run
   */
  static boolean isIngestRequest(@NotNull HttpServletRequest request) {
    return INGEST_PATHS.matches(PathContainer.parsePath(request.getRequestURI()));
  }
}
