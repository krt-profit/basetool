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

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintCatalogueMetadata;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintDto;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Verifies recipe evidence load state, patch provenance and imported quality bounds. */
class BlueprintCatalogueMetadataTest {

  @Test
  void outOfRangeSourceTreeAndAspectQualitiesAreClampedWithoutDiscardingTheRecipe() {
    ScWikiBlueprintDto dto =
        JsonMapper.builder()
            .build()
            .readValue(
                """
                {"tiers":[{"requirements":{"kind":"resource","min_quality":1500,
                  "modifiers":[{"quality_range":{"min":-5,"max":2000},
                    "value_segments":[{"quality_min":-10,"quality_max":1001}]}]}}],
                 "aspects":{"aspects":[{"input":{"kind":"item","min_quality":1001},
                   "initial_quality":-1,"slider_min":-5,"slider_max":2000}]}}
                """,
                ScWikiBlueprintDto.class);

    var metadata = dto.metadata();
    assertNotNull(metadata);
    var requirement = metadata.tiers().getFirst().requirements();
    assertEquals(1000, requirement.minQuality());
    var modifier = requirement.modifiers().getFirst();
    assertEquals(0.0, modifier.qualityRange().min());
    assertEquals(1000.0, modifier.qualityRange().max());
    assertEquals(0.0, modifier.valueSegments().getFirst().qualityMin());
    assertEquals(1000.0, modifier.valueSegments().getFirst().qualityMax());
    var aspect = metadata.aspects().aspects().getFirst();
    assertEquals(1000, aspect.input().minQuality());
    assertEquals(0, aspect.initialQuality());
    assertEquals(0, aspect.sliderMin());
    assertEquals(1000, aspect.sliderMax());
  }

  @Test
  void aListPayloadWithoutOptionalDetailSectionsDoesNotPretendTheyWereLoadedEmpty() {
    ScWikiBlueprintDto dto =
        JsonMapper.builder()
            .build()
            .readValue(
                "{\"game_version\":\"4.10.2\",\"ingredients\":[]}", ScWikiBlueprintDto.class);

    assertNull(dto.metadata());
  }

  @Test
  void aLoadedEmptyMissionListDoesNotEraseAnOmittedMissionGroupOrTierTree() {
    BlueprintCatalogueMetadata previous =
        source(
            """
            {"game_version":"4.10.2","unlocking_missions":[{"title":"First mission"}],
             "unlocking_missions_grouped":[{"label":"Guaranteed","missions":[{"title":"First mission"}]}],
             "aspects":{"aspects":[{"name":"Frame"}]},"tiers":[{"tier_index":0}]}
            """);
    BlueprintCatalogueMetadata refresh =
        source(
            """
            {"game_version":"4.10.2","unlocking_missions":[]}
            """);

    BlueprintCatalogueMetadata merged = refresh.retainingMissingSections(previous);

    assertTrue(merged.unlockingMissions().isEmpty());
    assertEquals(previous.unlockingMissionGroups(), merged.unlockingMissionGroups());
    assertEquals(previous.aspects(), merged.aspects());
    assertEquals(previous.tiers(), merged.tiers());
    assertEquals(
        Set.of(BlueprintCatalogueMetadata.DetailSection.MISSIONS), refresh.loadedSections());
    assertEquals(
        Set.of(
            BlueprintCatalogueMetadata.DetailSection.MISSIONS,
            BlueprintCatalogueMetadata.DetailSection.MISSION_GROUPS,
            BlueprintCatalogueMetadata.DetailSection.ASPECTS,
            BlueprintCatalogueMetadata.DetailSection.TIERS),
        merged.loadedSections());
  }

  @Test
  void explicitlyEmptyMissionGroupsAndTiersClearOnlyTheirOwnLoadedSections() {
    BlueprintCatalogueMetadata previous =
        source(
            """
            {"game_version":"4.10.2","unlocking_missions":[{"title":"First mission"}],
             "unlocking_missions_grouped":[{"label":"Guaranteed"}],"tiers":[{"tier_index":0}]}
            """);
    BlueprintCatalogueMetadata refresh =
        source(
            """
            {"game_version":"4.10.2","unlocking_missions_grouped":[],"tiers":[]}
            """);

    BlueprintCatalogueMetadata merged = refresh.retainingMissingSections(previous);

    assertEquals(previous.unlockingMissions(), merged.unlockingMissions());
    assertTrue(merged.unlockingMissionGroups().isEmpty());
    assertTrue(merged.tiers().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> merged.unlockingMissions().clear());
    assertThrows(UnsupportedOperationException.class, () -> merged.loadedSections().clear());
  }

  @Test
  void missingSectionsAreNotBorrowedFromAnotherGamePatch() {
    BlueprintCatalogueMetadata previous =
        source(
            """
            {"game_version":"4.10.2","unlocking_missions":[{"title":"Old mission"}],
             "unlocking_missions_grouped":[{"label":"Old rewards"}],"tiers":[{"tier_index":0}]}
            """);
    BlueprintCatalogueMetadata refresh =
        source(
            """
            {"game_version":"4.11.0","tiers":[]}
            """);

    BlueprintCatalogueMetadata merged = refresh.retainingMissingSections(previous);

    assertSame(refresh, merged);
    assertEquals("4.11.0", merged.gameVersion());
    assertTrue(merged.unlockingMissions().isEmpty());
    assertTrue(merged.unlockingMissionGroups().isEmpty());
    assertEquals(Set.of(BlueprintCatalogueMetadata.DetailSection.TIERS), merged.loadedSections());
  }

  /** Decodes optional detail evidence without supplying synthetic load-state markers. */
  private static BlueprintCatalogueMetadata source(String payload) {
    BlueprintCatalogueMetadata metadata =
        JsonMapper.builder().build().readValue(payload, ScWikiBlueprintDto.class).metadata();
    assertNotNull(metadata);
    return metadata;
  }
}
