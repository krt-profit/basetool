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

import de.greluc.krt.profit.basetool.backend.config.ApiDomains;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves that every OpenAPI schema name belongs to exactly one exposed Java type, and that the
 * guard detects two types sharing a name (REQ-API-018).
 */
class OpenApiSchemaNamesTest {

  /** How many own types the controllers exposed when the floor was last raised. */
  private static final int EXPOSED_TYPES_FLOOR = 441;

  /** A first fixture change set whose nested op shares its simple name with the second's. */
  static final class FixtureStockChanges {

    /**
     * A stock op.
     *
     * @param quantity the quantity
     */
    record Op(int quantity) {}

    /**
     * The change set.
     *
     * @param ops its ops
     */
    record Changes(List<Op> ops) {}
  }

  /** A second fixture change set with its own nested {@code Op}. */
  static final class FixtureShipChanges {

    /**
     * A ship op.
     *
     * @param name the ship name
     */
    record Op(String name) {}

    /**
     * The same op, explicitly named apart.
     *
     * @param name the ship name
     */
    @Schema(name = "FixtureShipOp")
    record NamedOp(String name) {}
  }

  /** A fixture controller exposing both ops, the way a real one would. */
  static final class FixtureController {

    /**
     * Answers a stock change set.
     *
     * @return nothing, only the signature matters
     */
    @GetMapping("/fixture/stock")
    FixtureStockChanges.Changes stock() {
      return null;
    }

    /**
     * Takes a ship op.
     *
     * @param op the op
     */
    @PostMapping("/fixture/ship")
    void ship(@RequestBody FixtureShipChanges.Op op) {}
  }

  /** The same controller with the second op renamed apart. */
  static final class FixtureRenamedController {

    /**
     * Answers a stock change set.
     *
     * @return nothing, only the signature matters
     */
    @GetMapping("/fixture/stock")
    FixtureStockChanges.Changes stock() {
      return null;
    }

    /**
     * Takes the explicitly named ship op.
     *
     * @param op the op
     */
    @PostMapping("/fixture/ship")
    void ship(@RequestBody FixtureShipChanges.NamedOp op) {}
  }

  /** Two exposed types with one simple name are a collision; an explicit name resolves it. */
  @Test
  @DisplayName("two exposed types sharing a schema name are caught, an explicit name resolves it")
  void aSharedSchemaNameIsCaught() {
    assertThat(ExposedTypes.collisions(ExposedTypes.bySchemaName(List.of(FixtureController.class))))
        .containsOnlyKeys("Op");
    assertThat(
            ExposedTypes.collisions(
                ExposedTypes.bySchemaName(List.of(FixtureRenamedController.class))))
        .isEmpty();
    assertThat(ExposedTypes.bySchemaName(List.of(FixtureRenamedController.class)))
        .containsKeys("FixtureShipOp", "Op", "Changes");
  }

  /** The backend's controllers expose no two types under one schema name. */
  @Test
  @DisplayName("every schema name of the backend belongs to exactly one exposed Java type")
  void everySchemaNameBelongsToOneType() {
    List<Class<?>> controllers = ExposedTypes.controllers();
    assertThat(controllers)
        .as("the scan found fewer controllers than the domain table names")
        .hasSizeGreaterThanOrEqualTo(ApiDomains.controllers().size());
    Map<String, Set<String>> byName = ExposedTypes.bySchemaName(controllers);

    assertThat(ExposedTypes.collisions(byName))
        .as(
            "two exposed Java types get one schema name, and springdoc documents only one of them."
                + " Give each an explicit @Schema(name = ...); keep the name an app build already"
                + " knows on the one the document showed so far")
        .isEmpty();
    assertThat(byName)
        .as("the scan reached fewer exposed types than it did when the floor was raised")
        .hasSizeGreaterThanOrEqualTo(EXPOSED_TYPES_FLOOR);
  }

  /** Every controller the scan finds is in the domain table, and the table names no stale one. */
  @Test
  @DisplayName("the domain table names exactly the backend's controllers")
  void theDomainTableNamesExactlyTheControllers() {
    Set<String> scanned = new TreeSet<>();
    ExposedTypes.controllers().forEach(controller -> scanned.add(controller.getSimpleName()));

    assertThat(new TreeSet<>(ApiDomains.controllers())).isEqualTo(scanned);
    assertThat(ApiDomains.all()).hasSize(22);
  }

  /**
   * Every exposed type the committed document names keeps that name: the explicit names chosen for
   * the three former collisions are in the document.
   *
   * @throws IOException if the document cannot be read
   */
  @Test
  @DisplayName("the formerly colliding schemas are documented under distinct names")
  void theFormerCollisionsAreDocumentedApart() throws IOException {
    try (InputStream in = OpenApiSchemaNamesTest.class.getResourceAsStream("/api/openapi.json")) {
      JsonNode schemas = new ObjectMapper().readTree(in).path("components").path("schemas");
      assertThat(schemas.propertyNames())
          .contains(
              "Op",
              "ExchangeBlueprintOp",
              "ExchangeShipOp",
              "Provenance",
              "ExchangeBlueprintProvenance",
              "Skipped");
      assertThat(schemas.path("Op").path("properties").has("expectedQuantity")).isTrue();
      assertThat(schemas.path("ExchangeShipOp").path("properties").has("shipType")).isTrue();
      assertThat(schemas.path("Provenance").path("properties").has("observedAt")).isTrue();
    }
  }
}
