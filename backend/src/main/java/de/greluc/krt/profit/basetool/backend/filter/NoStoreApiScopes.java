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
 * The GET families whose bodies must not be stored by anyone (REQ-SEC-031), in one place.
 *
 * <p><b>Why this is its own class rather than a constant on a filter.</b> Two filters need the same
 * answer for opposite reasons, and they sit in different packages. {@link ApiCacheControlFilter}
 * reads it to decide between {@code private, no-store} and {@code no-cache, must-revalidate}; the
 * ETag filter reads it to decide whether buffering the response could ever pay for itself. A second
 * copy of the list would be a divergence waiting to happen — the same argument ADR-0135 makes about
 * a second copy of an authorisation rule — and the failure would be silent in both directions: a
 * family added here but not there keeps paying for a buffer nobody can use, and a family added
 * there but not here is <em>downgraded</em> from {@code no-store} to a storable directive.
 *
 * <p><b>The list is load-bearing, not advisory.</b> Adding a sensitive GET family means adding it
 * here; {@link ApiCacheControlFilter} explains what a missing entry costs.
 *
 * <ul>
 *   <li>{@code bank} and {@code org-units/bank} — account balances, bookings and the ledger. Both
 *       spellings, because they are <em>different</em> surfaces: {@code /api/v1/bank/**} is the
 *       bank-employee one, while the member-facing account a client actually reads lives under
 *       {@code /api/v1/org-units/bank/**} and its transaction rows carry a {@code holderHandle};
 *   <li>{@code users} and {@code me} — member records, the only PII the API serves;
 *   <li>{@code notifications} — one member's personal feed, including the SSE stream;
 *   <li>{@code finance-entries} (both the standalone write family and the per-mission read) and
 *       {@code operations} — the mission/operation payout ledgers and their rollups;
 *   <li>{@code personal-inventory}, {@code personal-blueprints}, {@code inventory}, {@code hangar}
 *       and {@code refinery-orders} — a member's own holdings and the org stock/fleet they name
 *       members in;
 *   <li>{@code promotion} — a member's own evaluation and eligibility record.
 * </ul>
 *
 * <p>The Materialbörse ({@code material-exchange} / {@code material-requests}) is deliberately
 * <em>not</em> here: it is an org-wide shared board, and the handles it carries are the same public
 * callsign tuple the public mission roster already serves, so it belongs in the revalidate bucket
 * with the other shared listings.
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
   * Answers whether a request URI names one of the families above.
   *
   * <p>Matched against the decoded path rather than the raw {@code getRequestURI()} string
   * (REQ-SEC-029): the raw URI is percent-encoded while Spring MVC routes on the decoded path, so a
   * spelling such as {@code /api/v1/%62ank/accounts} must not slip out of the stricter bucket by
   * failing a literal prefix test.
   *
   * @param uri the raw request URI, as {@code HttpServletRequest#getRequestURI()} returns it;
   *     {@code null} answers {@code false} rather than throwing, because a request with no URI
   *     cannot be shown to belong to a sensitive family and both callers treat "unknown" as "apply
   *     the ordinary path".
   * @return {@code true} when the path belongs to a family whose body must not be stored.
   */
  public static boolean matches(@Nullable String uri) {
    if (uri == null) {
      return false;
    }
    PathContainer path = PathContainer.parsePath(uri);
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
