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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.config.PathControlInventory.Endpoint;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ActingMemberFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the pending-approval, terms-acceptance and acting-member path sets to exact, literal paths
 * that name real mappings (REQ-SEC-080).
 */
@SpringBootTest
class FilterExemptionSetsTest {

  /** The pending-approval gate's exemptions. */
  private static final List<String> PENDING_EXEMPT =
      List.of(
          "/api/v1/users/me/registration-status",
          "/api/v1/app/version-policy",
          "/api/v1/terms/document");

  /** The terms gate's exemptions. */
  private static final List<String> TERMS_EXEMPT =
      List.of(
          "/api/v1/terms/document",
          "/api/v1/terms/status",
          "/api/v1/terms/acceptance",
          "/api/v1/users/me/registration-status",
          "/api/v1/app/version-policy");

  /** The mappings under {@code /api/v1/terms} reviewed as gated by consent: the administration. */
  private static final List<String> TERMS_GATED =
      List.of("/api/v1/terms/admin", "/api/v1/terms/admin/pending-count");

  /** The endpoints the ingest gateway may act on for a member. */
  private static final List<String> ACTING_PATHS =
      List.of(
          "/api/v1/exchange/catalog/locations",
          "/api/v1/exchange/catalog/resolve",
          "/api/v1/exchange/me/account-check",
          "/api/v1/exchange/me/blueprints",
          "/api/v1/exchange/me/blueprints/changes",
          "/api/v1/exchange/me/drafts/blueprints",
          "/api/v1/exchange/me/drafts/refinery-orders",
          "/api/v1/exchange/me/installation",
          "/api/v1/exchange/me/org-demand",
          "/api/v1/exchange/me/ships",
          "/api/v1/exchange/me/ships/changes",
          "/api/v1/exchange/me/stock",
          "/api/v1/exchange/me/stock/changes");

  @Autowired private WebApplicationContext context;

  @Test
  @DisplayName("the pending-approval gate exempts exactly its three paths")
  void pendingExemptionsAreExact() {
    List<String> actual = new ArrayList<>(PendingApprovalAccessFilter.ANONYMOUS_READ_PATHS);
    actual.add(PendingApprovalAccessFilter.SELF_STATUS_PATH);

    assertThat(actual).containsExactlyInAnyOrderElementsOf(PENDING_EXEMPT);
  }

  @Test
  @DisplayName("the terms gate exempts exactly its five paths")
  void termsExemptionsAreExact() {
    assertThat(TermsAcceptanceAccessFilter.EXEMPT_PATHS)
        .containsExactlyInAnyOrderElementsOf(TERMS_EXEMPT);
  }

  @Test
  @DisplayName("the acting-member filter acts on exactly its thirteen paths")
  void actingPathsAreExact() {
    assertThat(ActingMemberFilter.ACTING_PATHS).containsExactlyInAnyOrderElementsOf(ACTING_PATHS);
  }

  @Test
  @DisplayName("every exempt path is a literal that names a real mapping")
  void everyExemptPathIsALiteralRealMapping() {
    List<String> all =
        Stream.of(PENDING_EXEMPT, TERMS_EXEMPT, ACTING_PATHS).flatMap(List::stream).toList();

    assertThat(all).noneMatch(path -> path.contains("*") || path.contains("{"));
    assertThat(PathControlInventory.unmappedPaths(all, PathControlInventory.dispatcher(context)))
        .isEmpty();
  }

  @Test
  @DisplayName("every mapping under /api/v1/terms is exempt from the terms gate, or decided")
  void everyTermsMappingIsListed() {
    List<Endpoint> endpoints = PathControlInventory.dispatcher(context);

    assertThat(endpoints)
        .filteredOn(e -> PathControlInventory.isUnder(e.pattern(), "/api/v1/terms"))
        .hasSizeGreaterThanOrEqualTo(3);
    List<String> decided = new ArrayList<>(TERMS_EXEMPT);
    decided.addAll(TERMS_GATED);
    assertThat(PathControlInventory.unlistedBelow("/api/v1/terms", decided, endpoints))
        .as(
            "a new /api/v1/terms mapping is gated by consent unless TermsAcceptanceAccessFilter"
                + " lists it; decide which, and list it in TERMS_EXEMPT or TERMS_GATED")
        .isEmpty();
  }

  @Test
  @DisplayName("every exchange mapping is an acting path")
  void everyExchangeMappingIsAnActingPath() {
    assertThat(
            PathControlInventory.unlistedBelow(
                "/api/v1/exchange", ACTING_PATHS, PathControlInventory.dispatcher(context)))
        .isEmpty();
  }
}
