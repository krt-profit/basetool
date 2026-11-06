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

package de.greluc.krt.profit.basetool.backend.exchange;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.api.CommittedOpenApi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fence for the Wiki/UEX catalogue evidence (REQ-XCH-050): the retired-row snapshot and the Wiki
 * item metadata are on no exchange or relay surface, and the commodity mining data travels only on
 * the backend relay of a draft preview, which the ingest stages for the member and never returns to
 * the client.
 */
class ExchangeCatalogueEvidenceFenceTest {

  private static final Path SCHEMAS = Path.of("../ingest/src/main/resources/exchange/v1/schemas");
  private static final Path PUBLISHED_OPENAPI =
      Path.of("../ingest/src/main/resources/api/exchange-v1.openapi.json");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Catalogue evidence that stays inside the Basetool's admin surface. */
  private static final List<String> INTERNAL_ONLY = List.of("retiredSnapshot", "wikiMetadata");

  /** Catalogue facts allowed on the relay of a staged draft preview, never published. */
  private static final List<String> RELAY_ONLY = List.of("miningData", "CommodityMiningData");

  @Test
  void theRelayDocumentCarriesNoInternalCatalogueEvidence() {
    assertThat(names(CommittedOpenApi.relay())).doesNotContainAnyElementsOf(INTERNAL_ONLY);
  }

  @Test
  void thePublishedExchangeCarriesNoCatalogueEvidence() throws IOException {
    Set<String> published = names(JSON.readTree(PUBLISHED_OPENAPI.toFile()));
    try (Stream<Path> files = Files.list(SCHEMAS)) {
      for (Path schema : files.filter(file -> file.toString().endsWith(".json")).toList()) {
        published.addAll(names(JSON.readTree(schema.toFile())));
      }
    }

    assertThat(published)
        .doesNotContainAnyElementsOf(INTERNAL_ONLY)
        .doesNotContainAnyElementsOf(RELAY_ONLY);
  }

  @Test
  void theMiningDataReachesTheRelayOnlyThroughADraftPreview() {
    JsonNode schemas = CommittedOpenApi.relay().path("components").path("schemas");
    Set<String> holders = new TreeSet<>();
    for (Map.Entry<String, JsonNode> schema : schemas.properties()) {
      if (schema.getValue().path("properties").has("miningData")) {
        holders.add(schema.getKey());
      }
    }

    assertThat(holders).containsExactly("MaterialDto");
  }

  /**
   * Collects every property and schema name of a JSON document.
   *
   * @param document the document
   * @return the names
   */
  private static @NotNull Set<String> names(@NotNull JsonNode document) {
    Set<String> names = new TreeSet<>();
    collect(document, names);
    return names;
  }

  private static void collect(@NotNull JsonNode node, @NotNull Set<String> out) {
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> property : node.properties()) {
        out.add(property.getKey());
        collect(property.getValue(), out);
      }
    } else if (node.isArray()) {
      node.forEach(child -> collect(child, out));
    }
  }
}
