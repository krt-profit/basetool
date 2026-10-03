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

import de.greluc.krt.profit.basetool.backend.filter.NoStoreApiScopes.Caching;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.PathContainer;

/** Tests how the API family classification answers for each path spelling (REQ-SEC-031). */
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
        "/api/v1/promotion/eligibility",
        "/api/v1/admin/person-search",
        "/api/v1/audit/MISSION",
        "/api/v1/connected-apps",
        "/api/v1/exchange/me/stock",
        "/api/v1/leitung/view",
        "/api/v1/live-sync/stream",
        "/api/v1/material-exchange/offers",
        "/api/v1/material-exchange/releasable-items",
        "/api/v1/material-requests",
        "/api/v1/notification-rules",
        "/api/v1/orders",
        "/api/v1/orders/00000000-0000-4000-8000-000000000000/item-blueprint-owners",
        "/api/v1/org-chart",
        "/api/v1/special-commands/00000000-0000-4000-8000-000000000000/members",
        "/api/v1/terms/status"
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
        "/api/v1/job-types",
        "/api/v1/org-units/active",
        "/api/v1/special-commands",
        "/api/v1/orders/item-catalog",
        "/api/v1/exchange/catalog/locations",
        "/api/v1/terms/document",
        "/api/v2/system/ping",
        "/actuator/health",
        "/"
      })
  @DisplayName("the shared listings, the catalogues and non-API paths stay out of it")
  void sharedSurfacesDoNotMatch(String uri) {
    assertThat(NoStoreApiScopes.matches(uri)).isFalse();
  }

  @ParameterizedTest(name = "{0} is {1}")
  @CsvSource({
    "/api/v1/terms/document, REVALIDATE",
    "/api/v1/terms/status, NO_STORE",
    "/api/v1/orders/item-catalog/00000000-0000-4000-8000-000000000000/blueprints, REVALIDATE",
    "/api/v1/orders/requested, NO_STORE",
    "/api/v1/exchange/catalog/locations, REVALIDATE",
    "/api/v1/exchange/me/installation, NO_STORE",
    "/api/v1/org-units/bank/balances, NO_STORE",
    "/api/v1/org-units/active, REVALIDATE",
    "/api/v1/missions/00000000-0000-4000-8000-000000000000/finance-entries/sum, NO_STORE",
    "/api/v1/missions/00000000-0000-4000-8000-000000000000, REVALIDATE",
    "/api/v1/special-commands/00000000-0000-4000-8000-000000000000/members, NO_STORE",
    "/api/v1/special-commands/00000000-0000-4000-8000-000000000000, REVALIDATE",
    "/api/v1/squadrons/00000000-0000-4000-8000-000000000000/members, NO_STORE",
    "/api/v1/squadrons/00000000-0000-4000-8000-000000000000/kommando-groups, REVALIDATE"
  })
  @DisplayName("the most specific family decides, so a sub-family can differ from its parent")
  void theMostSpecificFamilyDecides(String uri, Caching expected) {
    assertThat(NoStoreApiScopes.classify(PathContainer.parsePath(uri))).isEqualTo(expected);
  }

  @Test
  @DisplayName("an unclassified API path is not classified and fails safe to no-store")
  void anUnclassifiedApiPathFailsSafe() {
    PathContainer unclassified = PathContainer.parsePath("/api/v1/not-a-family/x");

    assertThat(NoStoreApiScopes.classify(unclassified)).isNull();
    assertThat(NoStoreApiScopes.matches(unclassified)).isTrue();
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
  @DisplayName("no family is classified both ways")
  void noFamilyIsClassifiedTwice() {
    Set<String> both = new HashSet<>(NoStoreApiScopes.noStoreFamilies());
    both.retainAll(NoStoreApiScopes.revalidateFamilies());

    assertThat(both).isEmpty();
  }

  @Test
  @DisplayName("the lists cannot be quietly emptied")
  void theListsHaveAFloor() {
    assertThat(NoStoreApiScopes.size())
        .as(
            "the number of no-store families. Raise it here when you add one, and add the path to"
                + " sensitiveFamiliesMatch in the same change")
        .isEqualTo(28);
    assertThat(NoStoreApiScopes.revalidateFamilies()).hasSize(33);
  }
}
