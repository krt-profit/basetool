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

package de.greluc.krt.profit.basetool.ingest.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway's route table and the committed OpenAPI document name the same authenticated routes
 * with the same capabilities (REQ-XCH-001, REQ-XCH-004).
 */
class ExchangeRoutesContractTest {

  private static final String OPENAPI = "/api/exchange-v1.openapi.json";
  private static final Set<String> ANONYMOUS =
      Set.of("/exchange/v1/openapi.json", "/exchange/v1/schemas/{name}.schema.json");

  @Test
  void theTableMatchesTheDocumentRouteForRouteAndScopeForScope() throws IOException {
    Set<String> documented = new TreeSet<>();
    for (Map.Entry<String, JsonNode> path : openApi().get("paths").properties()) {
      if (ANONYMOUS.contains(path.getKey())) {
        continue;
      }
      for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
        String method = operation.getKey().toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST").contains(method)) {
          continue;
        }
        documented.add(method + " " + path.getKey());
        ExchangeRoutes.Route route =
            ExchangeRoutes.find(method, path.getKey())
                .orElseThrow(
                    () -> new AssertionError("no table entry for " + method + " " + path.getKey()));
        List<String> scopes = scopes(operation.getValue());
        if (scopes.size() == 1) {
          assertThat(route.capability())
              .as(method + " " + path.getKey())
              .isEqualTo(scopes.getFirst());
        } else {
          assertThat(route.capability())
              .as(method + " " + path.getKey())
              .isEqualTo(ExchangeRoutes.ANY);
        }
      }
    }
    Set<String> table = new TreeSet<>();
    for (ExchangeRoutes.Route route : ExchangeRoutes.ROUTES) {
      table.add(route.method().name() + " " + route.pattern().getPatternString());
    }
    assertThat(table).isEqualTo(documented);
  }

  @Test
  void theChangeAndDraftRoutesAreTheWrites() {
    Set<String> writes = new TreeSet<>();
    for (ExchangeRoutes.Route route : ExchangeRoutes.ROUTES) {
      if (route.write()) {
        writes.add(route.pattern().getPatternString());
      }
    }
    assertThat(writes)
        .containsExactly(
            "/exchange/v1/me/blueprints/changes",
            "/exchange/v1/me/drafts/blueprints",
            "/exchange/v1/me/drafts/refinery-orders",
            "/exchange/v1/me/ships/changes",
            "/exchange/v1/me/stock/changes");
    assertThat(
            ExchangeRoutes.find("POST", "/exchange/v1/me/account-check")
                .orElseThrow()
                .accountCheck())
        .isTrue();
    assertThat(
            ExchangeRoutes.find("POST", "/exchange/v1/catalog/resolve")
                .orElseThrow()
                .accountCheck())
        .isFalse();
  }

  /**
   * Returns the scopes an operation's {@code memberToken} requirement lists.
   *
   * @param operation the operation
   * @return the scopes
   */
  private static List<String> scopes(JsonNode operation) {
    List<String> scopes = new ArrayList<>();
    for (JsonNode requirement : operation.path("security")) {
      for (JsonNode scope : requirement.path("memberToken")) {
        scopes.add(scope.stringValue());
      }
    }
    return scopes;
  }

  /**
   * Reads the committed OpenAPI document.
   *
   * @return its root
   * @throws IOException if it cannot be read
   */
  private JsonNode openApi() throws IOException {
    try (InputStream in = getClass().getResourceAsStream(OPENAPI)) {
      assertThat(in).isNotNull();
      return JsonMapper.builder().build().readTree(in);
    }
  }
}
