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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ItemCatalogueMetadata;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Checks optional Wiki item evidence against small captured public-response fixtures. */
class ScWikiItemMetadataTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void physicalCargoAndDisplayGeometryAndColorRelationshipsRemainDistinct() throws IOException {
    ScWikiItemDto source = fixture("scwiki-item-metadata.json");
    ItemCatalogueMetadata metadata =
        source
            .withRequestedIncludes(Set.of(ItemCatalogueMetadata.Relationship.RELATED_ITEMS))
            .metadata();

    assertEquals(ItemCatalogueMetadata.Source.SC_WIKI, metadata.source());
    assertEquals("4.10.2-LIVE.12881860", metadata.gameVersion());
    assertEquals(0.072, metadata.geometry().physical().width());
    assertEquals(0.06, metadata.geometry().cargo().width());
    assertEquals(0.75, metadata.geometry().ui().width());
    assertEquals(0.019, metadata.geometry().volumeScu());
    assertEquals(
        UUID.fromString("6b1fb5e8-f9ec-49bc-befb-c81b7c08c5ea"),
        metadata.relatedItems().baseItem().uuid());
    assertFalse(metadata.relatedItems().byColor().isEmpty());
    assertFalse(metadata.ports().isEmpty());
    assertEquals(
        Set.of(ItemCatalogueMetadata.Relationship.RELATED_ITEMS), metadata.requestedIncludes());
    assertThrows(UnsupportedOperationException.class, () -> metadata.ports().clear());
    assertThrows(UnsupportedOperationException.class, () -> metadata.requestedIncludes().clear());
  }

  @Test
  void derivedUexPriceEvidenceKeepsItsProvenanceAndLinkedLocation() throws IOException {
    ItemCatalogueMetadata metadata = fixture("scwiki-item-availability.json").metadata();

    assertEquals(1, metadata.images().size());
    assertEquals("cstone.space", metadata.images().getFirst().source());
    ItemCatalogueMetadata.UexPriceEvidence price = metadata.uexPriceEvidence().getFirst();
    assertEquals(ItemCatalogueMetadata.PriceOrigin.UEX_VIA_SC_WIKI, price.origin());
    assertFalse(price.authoritative());
    assertEquals(new BigDecimal("1290"), price.priceBuy());
    assertEquals(766, price.terminalId());
    assertEquals(UUID.fromString("a66119fa-6d93-4e56-bcd1-c9bd2c50beb7"), price.location().uuid());
    assertNotNull(price.updatedAt());
    assertNotNull(metadata.sourceUpdatedAt());
    assertTrue(metadata.requestedIncludes().isEmpty());
  }

  @Test
  void metadataRoundTripsWithoutDependingOnVendorJsonNames() throws IOException {
    ItemCatalogueMetadata metadata = fixture("scwiki-item-availability.json").metadata();
    String stored = MAPPER.writeValueAsString(metadata);

    assertEquals(metadata, MAPPER.readValue(stored, ItemCatalogueMetadata.class));
  }

  private static ScWikiItemDto fixture(String name) throws IOException {
    try (InputStream input =
        ScWikiItemMetadataTest.class.getResourceAsStream("/catalogue/" + name)) {
      assertNotNull(input);
      return MAPPER.treeToValue(MAPPER.readTree(input).path("data"), ScWikiItemDto.class);
    }
  }
}
