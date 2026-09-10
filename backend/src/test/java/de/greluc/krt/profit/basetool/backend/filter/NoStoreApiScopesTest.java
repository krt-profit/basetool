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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The list two filters read, tested once instead of twice.
 *
 * <p>{@link ApiCacheControlFilterTest} covers what the directive does to a response and {@code
 * StreamAwareShallowEtagHeaderFilterTest} covers what it does to the ETag buffer. What is left, and
 * belongs here, is the matching itself: which spellings of a path the list recognises, and which it
 * deliberately does not.
 */
class NoStoreApiScopesTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/bank",
        "/api/v1/bank/accounts",
        "/api/v1/org-units/bank/transactions",
        "/api/v1/users",
        "/api/v1/users/me",
        "/api/v1/me/capabilities",
        "/api/v1/notifications",
        "/api/v1/notifications/stream",
        "/api/v1/finance-entries",
        "/api/v1/missions/00000000-0000-4000-8000-000000000000/finance-entries",
        "/api/v1/operations/00000000-0000-4000-8000-000000000000/payouts",
        "/api/v1/personal-inventory",
        "/api/v1/personal-blueprints",
        "/api/v1/inventory/mission/00000000-0000-4000-8000-000000000000",
        "/api/v1/hangar/ships",
        "/api/v1/refinery-orders/all",
        "/api/v1/promotion/eligibility"
      })
  @DisplayName("every sensitive family is recognised, container path included")
  void sensitiveFamiliesMatch(String uri) {
    // The container itself matters as much as its members: `/api/v1/bank/**` has to answer for
    // `/api/v1/bank` too, or the family's own index would be the one path that escapes the rule.
    assertThat(NoStoreApiScopes.matches(uri)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/missions",
        "/api/v1/missions/search",
        "/api/v1/materials/matrix",
        "/api/v1/material-exchange/offers",
        "/api/v1/material-requests",
        "/api/v1/job-types",
        "/api/v1/live-sync/changed",
        "/actuator/health",
        "/"
      })
  @DisplayName("the shared boards and the catalogues stay out of it")
  void sharedSurfacesDoNotMatch(String uri) {
    // The Materialboerse is the deliberate near-miss: an org-wide board carrying the same public
    // callsign tuple the mission roster already serves, so it belongs in the revalidate bucket.
    assertThat(NoStoreApiScopes.matches(uri)).isFalse();
  }

  @Test
  @DisplayName("a percent-encoded spelling does not escape the family")
  void percentEncodedPathsStillMatch() {
    // REQ-SEC-029. getRequestURI() is raw while Spring MVC routes on the decoded path, so a literal
    // prefix test let `/api/v1/%62ank/accounts` reach the handler outside the stricter bucket.
    // PathContainer.parsePath decodes each segment, which is why this answers true.
    assertThat(NoStoreApiScopes.matches("/api/v1/%62ank/accounts")).isTrue();
    assertThat(NoStoreApiScopes.matches("/api/v1/%75sers/me")).isTrue();
  }

  @Test
  @DisplayName("a dot segment does NOT escape a family, because these patterns end in /**")
  void dotSegmentsStillMatch() {
    // Worth pinning, because the neighbouring guard behaves the opposite way and the difference is
    // the pattern shape rather than the code. StreamAwareShallowEtagHeaderFilter matches its two
    // streaming endpoints EXACTLY, so `/api/v1/live-sync/./stream` slips past it; every pattern
    // here ends in `/**`, which matches any segments at all -- a literal `.` among them. So the
    // stricter of the two answers, `no-store`, is the one that survives an unnormalised spelling,
    // which is the direction that matters (REQ-SEC-031).
    assertThat(NoStoreApiScopes.matches("/api/v1/bank/./accounts")).isTrue();
    assertThat(NoStoreApiScopes.matches("/api/v1/users/../users/me")).isTrue();
  }

  @Test
  @DisplayName("a null URI answers false rather than throwing")
  void nullUriIsFalse() {
    // Both callers hand this whatever getRequestURI() returned. Answering rather than throwing is
    // what lets each of them treat "unknown" as "apply the ordinary path".
    assertThat(NoStoreApiScopes.matches(null)).isFalse();
  }

  @Test
  @DisplayName("the list cannot be quietly emptied")
  void theListHasAFloor() {
    // Without this, deleting every pattern would leave both filters green: one would stop writing
    // `no-store` and the other would stop skipping, and no case above would notice.
    assertThat(NoStoreApiScopes.size()).isEqualTo(14);
  }
}
