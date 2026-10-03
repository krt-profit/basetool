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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proves the exchange fence (REQ-XCH-039): the split of the generated model into the published and
 * the relay document, the join of the two, the committed documents' shape, and the staleness check
 * of a committed document.
 */
class ExchangeFenceTest {

  /** A full model with one relay path, one other path and four schemas. */
  private static final String FULL =
      """
      {
        "openapi": "3.1.0",
        "info": {"description": "d", "title": "t", "version": "1.0"},
        "security": [{"bearer-jwt": []}],
        "tags": [{"name": "exchange"}, {"name": "identity"}],
        "paths": {
          "/api/v1/exchange/me/stock": {"get": {"tags": ["exchange"], "responses": {
            "200": {"content": {"*/*": {"schema": {"$ref": "#/components/schemas/Page"}}}},
            "429": {"headers": {"Retry-After": {"$ref": "#/components/headers/Retry-After"}}},
            "500": {"content": {"application/problem+json": {"schema": {"$ref": "#/components/schemas/Problem"}}}}}}},
          "/api/v1/users/me": {"get": {"tags": ["identity"], "responses": {
            "200": {"content": {"*/*": {"schema": {"$ref": "#/components/schemas/User"}}}},
            "500": {"content": {"application/problem+json": {"schema": {"$ref": "#/components/schemas/Problem"}}}}}}}
        },
        "components": {
          "headers": {"Retry-After": {"schema": {"type": "integer"}}},
          "schemas": {
            "Lot": {"type": "object"},
            "Page": {"type": "object", "properties": {"items": {"type": "array", "items": {"$ref": "#/components/schemas/Lot"}}}},
            "Problem": {"type": "object"},
            "User": {"type": "object"}
          },
          "securitySchemes": {"bearer-jwt": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}}
        }
      }
      """;

  /** Reads the fixtures. */
  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * The relay path, the components it reaches and the security schemes go to the relay document.
   */
  @Test
  @DisplayName("the split moves the relay path and the components only it reaches")
  void theSplitMovesTheRelaySurface() {
    JsonNode full = mapper.readTree(FULL);
    ExchangeFence.Split split = ExchangeFence.split(full);

    assertThat(split.published().path("paths").propertyNames()).containsExactly("/api/v1/users/me");
    assertThat(split.published().path("components").path("schemas").propertyNames())
        .containsExactly("Problem", "User");
    assertThat(split.published().path("components").has("headers")).isFalse();
    assertThat(split.published().path("tags").findValuesAsString("name"))
        .containsExactly("identity");

    assertThat(split.relay().path("paths").propertyNames())
        .containsExactly("/api/v1/exchange/me/stock");
    assertThat(split.relay().path("components").path("schemas").propertyNames())
        .containsExactly("Lot", "Page", "Problem");
    assertThat(split.relay().path("components").path("headers").propertyNames())
        .containsExactly("Retry-After");
    assertThat(split.relay().path("tags").findValuesAsString("name")).containsExactly("exchange");
    assertThat(split.relay().path("info").path("title").asString())
        .isEqualTo(ExchangeFence.RELAY_TITLE);

    for (JsonNode document : List.of(split.published(), split.relay())) {
      assertThat(document.path("components").path("securitySchemes").has("bearer-jwt")).isTrue();
      assertThat(document.path("security")).isEqualTo(full.path("security"));
      assertThat(ExchangeFence.danglingReferences(document)).isEmpty();
    }
    assertThat(full).as("the split leaves its input alone").isEqualTo(mapper.readTree(FULL));
    assertThat(ExchangeFence.merge(split.published(), split.relay()).path("paths"))
        .isEqualTo(full.path("paths"));
  }

  /** The join refuses two documents that disagree. */
  @Test
  @DisplayName("the join refuses a path in both documents and a component described twice")
  void theJoinRefusesAConflict() {
    ExchangeFence.Split split = ExchangeFence.split(mapper.readTree(FULL));

    assertThatThrownBy(() -> ExchangeFence.merge(split.relay(), split.relay()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/api/v1/exchange/me/stock");
    ObjectNode other = split.relay().deepCopy();
    ((ObjectNode) other.path("paths")).removeAll();
    ((ObjectNode) other.path("components").path("schemas").path("Problem")).put("title", "x");
    assertThatThrownBy(() -> ExchangeFence.merge(split.published(), other))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schemas/Problem");
  }

  /** A reference to a component the document lacks is found. */
  @Test
  @DisplayName("a dangling reference is found")
  void aDanglingReferenceIsFound() {
    ObjectNode published = ExchangeFence.split(mapper.readTree(FULL)).published();
    ((ObjectNode) published.path("components").path("schemas")).remove("User");

    assertThat(ExchangeFence.danglingReferences(published))
        .containsExactly("#/components/schemas/User");
  }

  /** The committed documents are what the split of their join gives, and pass their assertions. */
  @Test
  @DisplayName("the committed documents are the split of their join and pass their assertions")
  void theCommittedDocumentsAreTheSplitOfTheirJoin() {
    JsonNode published = CommittedOpenApi.published();
    JsonNode relay = CommittedOpenApi.relay();
    ExchangeFence.Split split = ExchangeFence.split(CommittedOpenApi.merged());

    assertThat(split.published()).isEqualTo(published);
    assertThat(split.relay()).isEqualTo(relay);
    assertThat(ExchangeFence.operationCount(relay)).isEqualTo(ExchangeFence.RELAY_OPERATIONS);
    assertThat(OpenApiDocumentAssertions.relayProblems(relay, ContractTiers.load())).isEmpty();
    assertThat(relay.path("components").path("schemas").size())
        .as("the relay document's schemas")
        .isGreaterThanOrEqualTo(64);
  }

  /**
   * An up-to-date file is left alone, a stale one fails without rewrite mode and is replaced with
   * it; line ends never count.
   *
   * @param directory an empty directory for the fixture
   * @throws IOException if the fixture cannot be written or read
   */
  @Test
  @DisplayName("a stale committed document fails the check, or is rewritten in rewrite mode")
  void aStaleDocumentFailsOrIsRewritten(@TempDir Path directory) throws IOException {
    JsonNode relay = ExchangeFence.split(mapper.readTree(FULL)).relay();
    String rendered = CommittedOpenApi.render(relay);
    assertThat(rendered).doesNotContain("\r").doesNotEndWith("\n");

    Path file = directory.resolve("exchange-relay.openapi.json");
    Files.writeString(file, rendered.replace("\n", "\r\n"), StandardCharsets.UTF_8);
    assertThat(CommittedOpenApi.refresh(file, rendered, false)).isFalse();

    String stale = rendered.replace("\"Lot\"", "\"Lots\"");
    Files.writeString(file, stale, StandardCharsets.UTF_8);
    assertThatThrownBy(() -> CommittedOpenApi.refresh(file, rendered, false))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("is stale");
    assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(stale);

    assertThat(CommittedOpenApi.refresh(file, rendered, true)).isTrue();
    assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(rendered);
    assertThat(CommittedOpenApi.refresh(file, rendered, false)).isFalse();

    Path missing = directory.resolve("missing.json");
    assertThatThrownBy(() -> CommittedOpenApi.refresh(missing, rendered, false))
        .isInstanceOf(AssertionError.class);
  }
}
