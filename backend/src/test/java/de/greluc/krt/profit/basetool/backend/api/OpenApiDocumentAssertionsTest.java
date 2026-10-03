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

package de.greluc.krt.profit.basetool.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.config.ContractTiers;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proves the generator's document assertions and the tier list parser able to fail, on planted
 * documents and lists (REQ-API-007, REQ-API-018).
 */
class OpenApiDocumentAssertionsTest {

  /** A sound two-operation document. */
  private static final String SOUND =
      """
      {
        "security": [{"bearer-jwt": []}],
        "components": {"securitySchemes": {"bearer-jwt": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}}},
        "tags": [{"name": "admin-system"}, {"name": "identity"}],
        "paths": {
          "/api/v1/app/version-policy": {"get": {"tags": ["admin-system"], "x-domain": "admin-system", "x-contract-tier": "T0", "security": []}},
          "/api/v1/terms/document": {"get": {"tags": ["identity"], "x-domain": "identity", "x-contract-tier": "T1", "security": []}}
        }
      }
      """;

  /** The same document with every kind of fault planted. */
  private static final String BROKEN =
      """
      {
        "security": [{"basic": []}],
        "components": {"securitySchemes": {"bearer-jwt": {"type": "http", "scheme": "basic"}}},
        "tags": [{"name": "App"}],
        "paths": {
          "/api/v1/app/version-policy": {"get": {"tags": ["App", "admin-system"], "x-domain": "admin-system", "x-contract-tier": "T2", "security": []}},
          "/api/v1/terms/document": {"get": {"tags": ["unassigned"], "x-domain": "unassigned", "x-contract-tier": "T1"}},
          "/api/v1/exchange/me/stock": {"get": {"tags": ["exchange"], "x-domain": "exchange", "x-contract-tier": "T2",
            "responses": {"200": {"content": {"*/*": {"schema": {"$ref": "#/components/schemas/Gone"}}}}}}}
        }
      }
      """;

  /** A relay document with every relay-specific fault planted. */
  private static final String BROKEN_RELAY =
      """
      {
        "security": [{"bearer-jwt": []}],
        "components": {"securitySchemes": {"bearer-jwt": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}}},
        "tags": [{"name": "exchange"}, {"name": "identity"}],
        "paths": {
          "/api/v1/exchange/me/stock": {"get": {"tags": ["exchange"], "x-domain": "exchange", "x-contract-tier": "T0", "security": [],
            "responses": {"200": {"content": {"*/*": {"schema": {"$ref": "#/components/schemas/Gone"}}}}}}},
          "/api/v1/exchange/me/ships": {"get": {"tags": ["exchange"], "x-domain": "exchange", "x-contract-tier": "T2"}},
          "/api/v1/terms/document": {"get": {"tags": ["identity"], "x-domain": "identity", "x-contract-tier": "T1"}}
        }
      }
      """;

  /** The tier list the fixtures are judged against. */
  private static final ContractTiers TIERS =
      ContractTiers.parse(
          List.of("T0 GET /api/v1/app/version-policy", "T1 GET /api/v1/terms/document"));

  /** A sound document passes with a floor it meets. */
  @Test
  @DisplayName("a sound document has no problem")
  void aSoundDocumentPasses() {
    assertThat(
            OpenApiDocumentAssertions.problems(
                read(SOUND), TIERS, Map.of("admin-system", 1, "identity", 1)))
        .isEmpty();
  }

  /** Every planted fault is reported. */
  @Test
  @DisplayName("a wrong scheme, tag, tier, anonymous set and floor are each reported")
  void everyPlantedFaultIsReported() {
    List<String> problems =
        OpenApiDocumentAssertions.problems(read(BROKEN), TIERS, Map.of("identity", 1));

    assertThat(problems)
        .anySatisfy(problem -> assertThat(problem).contains("bearer-jwt security scheme"))
        .anySatisfy(problem -> assertThat(problem).contains("document-wide security"))
        .anySatisfy(problem -> assertThat(problem).contains("anonymous operations"))
        .anySatisfy(problem -> assertThat(problem).contains("instead of exactly its domain"))
        .anySatisfy(problem -> assertThat(problem).contains("belongs to no known domain"))
        .anySatisfy(problem -> assertThat(problem).contains("contract tier 'T2', expected T0"))
        .anySatisfy(problem -> assertThat(problem).contains("tag list"))
        .anySatisfy(problem -> assertThat(problem).contains("below its floor"))
        .anySatisfy(problem -> assertThat(problem).contains("is an exchange relay path"))
        .anySatisfy(problem -> assertThat(problem).contains("lacks the component"));
  }

  /** Every planted relay fault is reported, and a relay operation fewer fails the committed one. */
  @Test
  @DisplayName(
      "a relay document with a foreign path, a wrong tier or count, or an anonymous op fails")
  void everyPlantedRelayFaultIsReported() {
    List<String> problems =
        OpenApiDocumentAssertions.relayProblems(read(BROKEN_RELAY), ContractTiers.load());

    assertThat(problems)
        .anySatisfy(problem -> assertThat(problem).contains("is not an exchange relay path"))
        .anySatisfy(problem -> assertThat(problem).contains("outside tier T0"))
        .anySatisfy(problem -> assertThat(problem).contains("anonymous operations"))
        .anySatisfy(problem -> assertThat(problem).contains("not exchange only"))
        .anySatisfy(problem -> assertThat(problem).contains("expected exactly 14"))
        .anySatisfy(problem -> assertThat(problem).contains("lacks the component"));

    ObjectNode relay = (ObjectNode) CommittedOpenApi.relay().deepCopy();
    ((ObjectNode) relay.path("paths")).remove("/api/v1/exchange/me/org-demand");
    assertThat(OpenApiDocumentAssertions.relayProblems(relay, ContractTiers.load()))
        .anySatisfy(problem -> assertThat(problem).contains("has 13 operations"));
  }

  /**
   * The committed document passes, so the generator's assertions hold on main.
   *
   * @throws IOException if the document cannot be read
   */
  @Test
  @DisplayName("the committed document passes the generator's assertions")
  void theCommittedDocumentPasses() throws IOException {
    try (InputStream in =
        OpenApiDocumentAssertionsTest.class.getResourceAsStream("/api/openapi.json")) {
      JsonNode document = new ObjectMapper().readTree(in);
      assertThat(
              OpenApiDocumentAssertions.problems(
                  document, ContractTiers.load(), OpenApiDocumentAssertions.DOMAIN_FLOOR))
          .isEmpty();
    }
  }

  /** A malformed or repeated tier line fails the parse; an unlisted operation is T2. */
  @Test
  @DisplayName("a malformed tier line fails and an unlisted operation is T2")
  void theTierListParses() {
    assertThat(TIERS.tierOf("get", "/api/v1/app/version-policy")).isEqualTo(ContractTiers.T0);
    assertThat(TIERS.tierOf("GET", "/api/v1/somewhere")).isEqualTo(ContractTiers.T2);
    assertThat(TIERS.operations(ContractTiers.T1)).containsExactly("GET /api/v1/terms/document");

    for (String line :
        List.of(
            "T2 GET /api/v1/x",
            "T1 get /api/v1/x",
            "T1 GET api/v1/x",
            "T1 GET /api/v1/x extra",
            "# T1 GET /api/v1/x")) {
      assertThatThrownBy(() -> ContractTiers.parse(List.of("", line)))
          .as(line)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("line 2");
    }
    assertThatThrownBy(() -> ContractTiers.parse(List.of("T1 GET /api/v1/x", "T0 GET /api/v1/x")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("second time");
  }

  /**
   * Parses a fixture document.
   *
   * @param json the document
   * @return the parsed tree
   */
  private static JsonNode read(String json) {
    return new ObjectMapper().readTree(json);
  }
}
