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

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the declared-break ledger and the previous-release comparison able to fail, on planted
 * documents and ledgers (REQ-API-017).
 */
class DeclaredBreaksTest {

  /** A previous release serving one read, one write and one operation that a wave retires. */
  private static final String PREVIOUS =
      """
      {
        "paths": {
          "/api/v1/things": {
            "get": {
              "parameters": [
                {"name": "page", "in": "query", "schema": {"type": "integer"}},
                {"name": "q", "in": "query", "schema": {"type": "string"}}
              ],
              "responses": {"200": {"content": {"*/*": {"schema": {"$ref": "#/components/schemas/Thing"}}}}}
            },
            "post": {
              "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/ThingWrite"}}}},
              "responses": {"201": {"content": {"*/*": {"schema": {"$ref": "#/components/schemas/Thing"}}}}}
            }
          },
          "/api/v1/things/{id}/slim": {
            "get": {"responses": {"200": {"content": {"*/*": {"schema": {"$ref": "#/components/schemas/Thing"}}}}}}
          }
        },
        "components": {
          "schemas": {
            "Thing": {
              "required": ["id"],
              "properties": {
                "id": {"type": "string", "format": "uuid"},
                "name": {"type": "string"},
                "count": {"type": "integer"}
              }
            },
            "ThingWrite": {"required": ["name"], "properties": {"name": {"type": "string"}}}
          }
        }
      }
      """;

  /** The same surface after a wave: one field retyped, one gone, one parameter and one op gone. */
  private static final String CURRENT =
      """
      {
        "paths": {
          "/api/v1/things": {
            "get": {
              "parameters": [{"name": "page", "in": "query", "schema": {"type": "integer"}}],
              "responses": {"200": {"content": {"*/*": {"schema": {"$ref": "#/components/schemas/Thing"}}}}}
            },
            "post": {
              "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/ThingWrite"}}}},
              "responses": {"201": {"content": {"*/*": {"schema": {"$ref": "#/components/schemas/Thing"}}}}}
            }
          },
          "/api/v1/things/new": {
            "get": {"responses": {"200": {"content": {"*/*": {"schema": {"$ref": "#/components/schemas/Thing"}}}}}}
          }
        },
        "components": {
          "schemas": {
            "Thing": {
              "required": ["id"],
              "properties": {
                "id": {"type": "string", "format": "uuid"},
                "name": {"type": "string"},
                "count": {"type": "string"}
              }
            },
            "ThingWrite": {"required": ["name"], "properties": {"name": {"type": "string"}}}
          }
        }
      }
      """;

  /** The operations the fixture freezes, including one only the current document serves. */
  private static final List<DeclaredBreaks.OperationKey> FROZEN =
      List.of(
          new DeclaredBreaks.OperationKey("GET", "/api/v1/things"),
          new DeclaredBreaks.OperationKey("POST", "/api/v1/things"),
          new DeclaredBreaks.OperationKey("GET", "/api/v1/things/{id}/slim"),
          new DeclaredBreaks.OperationKey("GET", "/api/v1/things/new"));

  /** An unchanged document yields no break. */
  @Test
  @DisplayName("an unchanged document has no break")
  void anUnchangedDocumentHasNoBreak() {
    JsonNode previous = read(PREVIOUS);

    assertThat(DeclaredBreaks.between(previous, previous, FROZEN)).isEmpty();
  }

  /** Every kind of break is found and reported until the ledger names it. */
  @Test
  @DisplayName("a retired operation, a retyped field and a dropped parameter are breaks")
  void everyKindOfBreakIsFoundUntilDeclared() {
    Map<DeclaredBreaks.Break, String> found =
        DeclaredBreaks.between(read(PREVIOUS), read(CURRENT), FROZEN);

    assertThat(found)
        .containsOnlyKeys(
            new DeclaredBreaks.Break("GET", "/api/v1/things", "Thing.count"),
            new DeclaredBreaks.Break("POST", "/api/v1/things", "Thing.count"),
            new DeclaredBreaks.Break("GET", "/api/v1/things", "query:q"),
            new DeclaredBreaks.Break("GET", "/api/v1/things/{id}/slim", "-"));
    assertThat(found.get(new DeclaredBreaks.Break("GET", "/api/v1/things", "Thing.count")))
        .isEqualTo("integer -> string");

    assertThat(DeclaredBreaks.undeclared(found, List.of())).hasSize(4);

    List<DeclaredBreaks.Entry> partial =
        DeclaredBreaks.parse(
            List.of(
                "GET /api/v1/things Thing.count 18",
                "GET /api/v1/things query:q 18",
                "GET /api/v1/things/{id}/slim - 18"));
    assertThat(DeclaredBreaks.undeclared(found, partial))
        .containsExactly("POST /api/v1/things Thing.count: integer -> string");

    List<DeclaredBreaks.Entry> complete =
        DeclaredBreaks.parse(
            List.of(
                "GET /api/v1/things Thing.count 18",
                "POST /api/v1/things Thing.count 18",
                "GET /api/v1/things query:q 18",
                "GET /api/v1/things/{id}/slim - 18"));
    assertThat(DeclaredBreaks.undeclared(found, complete)).isEmpty();
  }

  /** A line declaring a different field of the same operation does not accept the break. */
  @Test
  @DisplayName("a declaration accepts exactly its own operation and field")
  void aDeclarationAcceptsExactlyItsOwnOperationAndField() {
    Map<DeclaredBreaks.Break, String> found =
        DeclaredBreaks.between(read(PREVIOUS), read(CURRENT), FROZEN);

    List<DeclaredBreaks.Entry> wrongField =
        DeclaredBreaks.parse(List.of("GET /api/v1/things Thing.name 18"));
    List<DeclaredBreaks.Entry> wrongVerb =
        DeclaredBreaks.parse(List.of("PUT /api/v1/things Thing.count 18"));

    assertThat(DeclaredBreaks.undeclared(found, wrongField)).hasSize(4);
    assertThat(DeclaredBreaks.undeclared(found, wrongVerb)).hasSize(4);
  }

  /**
   * Every malformed ledger line fails the parse, naming its line.
   *
   * @param line the planted line
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "GET /api/v1/things Thing.count",
        "GET /api/v1/things Thing.count 18 extra",
        "get /api/v1/things Thing.count 18",
        "GET things Thing.count 18",
        "GET /api/v1/things/* - 18",
        "GET /api/v1/things Thing.* 18",
        "GET /api/v1/things ? 18",
        "GET /api/v1/things Thing.count eighteen",
        "GET /api/v1/things Thing.count 0",
        "# GET /api/v1/things Thing.count 18"
      })
  @DisplayName("a malformed or wildcard ledger line fails the parse")
  void aMalformedLineFails(String line) {
    assertThatThrownBy(() -> DeclaredBreaks.parse(List.of("", line)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("line 2");
  }

  /** A line breaking a T0 operation is reported; a T1 line is not. */
  @Test
  @DisplayName("a ledger line on a T0 operation is reported")
  void aTierZeroDeclarationIsReported() {
    List<DeclaredBreaks.Entry> ledger =
        DeclaredBreaks.parse(
            List.of("GET /api/v1/app/version-policy - 18", "GET /api/v1/things Thing.count 18"));

    assertThat(DeclaredBreaks.onTierZero(ledger, Set.of("GET /api/v1/app/version-policy")))
        .containsExactly("GET /api/v1/app/version-policy -");
    assertThat(DeclaredBreaks.onTierZero(ledger, Set.of())).isEmpty();
  }

  /** A previous document's T0 and T1 marks select its frozen operations. */
  @Test
  @DisplayName("the frozen operations of a tier-marked previous document are found")
  void theFrozenOperationsOfAMarkedDocumentAreFound() {
    JsonNode marked =
        read(
            """
            {"paths": {"/api/v1/a": {"get": {"x-contract-tier": "T0"}, "post": {"x-contract-tier": "T2"}},
                       "/api/v1/b": {"put": {"x-contract-tier": "T1"}}}}
            """);

    assertThat(DeclaredBreaks.frozenIn(marked))
        .containsExactlyInAnyOrder(
            new DeclaredBreaks.OperationKey("GET", "/api/v1/a"),
            new DeclaredBreaks.OperationKey("PUT", "/api/v1/b"));
    assertThat(DeclaredBreaks.frozenIn(read(PREVIOUS))).isEmpty();
  }

  /** A break declared twice fails the parse. */
  @Test
  @DisplayName("a break declared twice fails the parse")
  void aRepeatedDeclarationFails() {
    assertThatThrownBy(
            () ->
                DeclaredBreaks.parse(List.of("GET /api/v1/things - 18", "GET /api/v1/things - 19")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("repeats");
  }

  /** An empty ledger parses to no entry. */
  @Test
  @DisplayName("an empty ledger declares nothing")
  void anEmptyLedgerDeclaresNothing() {
    assertThat(DeclaredBreaks.parse(List.of())).isEmpty();
    assertThat(DeclaredBreaks.parse(List.of(""))).isEmpty();
    assertThat(Set.copyOf(DeclaredBreaks.parse(List.of("GET /api/v1/things - 18"))))
        .containsExactly(
            new DeclaredBreaks.Entry(
                new DeclaredBreaks.Break("GET", "/api/v1/things", DeclaredBreaks.WHOLE_OPERATION),
                18));
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
