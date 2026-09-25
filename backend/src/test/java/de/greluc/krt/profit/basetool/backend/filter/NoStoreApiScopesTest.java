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
    assertThat(NoStoreApiScopes.matches(uri)).isFalse();
  }

  @Test
  @DisplayName("a percent-encoded spelling does not escape the family")
  void percentEncodedPathsStillMatch() {
    assertThat(NoStoreApiScopes.matches("/api/v1/%62ank/accounts")).isTrue();
    assertThat(NoStoreApiScopes.matches("/api/v1/%75sers/me")).isTrue();
  }

  @Test
  @DisplayName("a dot segment does NOT escape a family, because these patterns end in /**")
  void dotSegmentsStillMatch() {
    assertThat(NoStoreApiScopes.matches("/api/v1/bank/./accounts")).isTrue();
    assertThat(NoStoreApiScopes.matches("/api/v1/users/../users/me")).isTrue();
  }

  @Test
  @DisplayName("a null URI answers false rather than throwing")
  void nullUriIsFalse() {
    assertThat(NoStoreApiScopes.matches((String) null)).isFalse();
  }

  @Test
  @DisplayName("the list cannot be quietly emptied")
  void theListHasAFloor() {
    assertThat(NoStoreApiScopes.size())
        .as(
            "the number of no-store families. Raise it here when you add one, and add the path to"
                + " sensitiveFamiliesMatch in the same change")
        .isEqualTo(14);
  }
}
