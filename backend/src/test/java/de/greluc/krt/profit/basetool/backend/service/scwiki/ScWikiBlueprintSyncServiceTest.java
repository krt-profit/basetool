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

package de.greluc.krt.profit.basetool.backend.service.scwiki;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintNameNormalizer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRequirementGroup;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRequirementModifier;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintSummaryProperty;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.BlueprintOutputNameOverrides;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiBlueprintGapFiller;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiBlueprintSyncService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncEventType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncReportService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintDismantleDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintIngredientDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintModifierDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintModifierRangeDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintModifierSegmentDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintQualityRangeDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintRequirementChildDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintRequirementGroupDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiBlueprintSummaryPropertyDto;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Unit tests for {@link ScWikiBlueprintSyncService} — the R4 blueprint recipe-graph sync. */
@ExtendWith(MockitoExtension.class)
class ScWikiBlueprintSyncServiceTest {

  @Mock private ScWikiClient scWikiClient;
  @Mock private BlueprintRepository blueprintRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private MaterialExternalAliasService aliasService;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private SyncReportService syncReportService;
  @Mock private ObjectProvider<ScWikiBlueprintSyncService> self;

  private ScWikiProperties properties;

  /** The configured keys, relative to the record's prefix; {@link #rebuild()} binds them. */
  private final Map<String, Object> config = new HashMap<>();

  private ScWikiBlueprintSyncService service;

  @BeforeEach
  void setUp() {
    config.putAll(Map.of("blueprint-sync-enabled", true));
    rebuild();
    lenient().when(syncReportService.beginRun()).thenReturn(UUID.randomUUID());
  }

  /**
   * Binds the properties record from {@link #config} and builds the object under test over it. The
   * record is immutable (BE-MOD-04), so a test that changes a key rebuilds.
   */
  private void rebuild() {
    properties = BoundProperties.bind(ScWikiProperties.class, config);
    service =
        new ScWikiBlueprintSyncService(
            scWikiClient,
            properties,
            blueprintRepository,
            materialRepository,
            aliasService,
            gameItemRepository,
            syncReportService,
            new ScWikiBlueprintGapFiller(syncReportService),
            new BlueprintOutputNameOverrides(new BlueprintNameNormalizer()),
            self);
    lenient().when(self.getObject()).thenReturn(service);
  }

  @Test
  void syncBlueprints_isNoOp_whenFeatureFlagOff() {
    config.put("blueprint-sync-enabled", false);
    rebuild();

    service.syncBlueprints();

    verifyNoInteractions(scWikiClient, blueprintRepository);
  }

  @Test
  void syncBlueprints_fallbackList_resolvesResourceToMaterialAndItemToGameItem() {
    UUID resourceUuid = UUID.randomUUID();
    UUID itemUuid = UUID.randomUUID();
    UUID outputUuid = UUID.randomUUID();
    ScWikiBlueprintIngredientDto resource =
        new ScWikiBlueprintIngredientDto("Agricium", "resource", resourceUuid, null, 0.36, null);
    ScWikiBlueprintIngredientDto item =
        new ScWikiBlueprintIngredientDto("Hadanite", "item", null, itemUuid, null, 7);
    ScWikiBlueprintDto dto = blueprint(outputUuid, List.of(resource, item), List.of());

    Material agricium = material("Agricium");
    GameItem hadanite = gameItem(itemUuid);
    GameItem output = gameItem(outputUuid);
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(blueprintRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());
    when(gameItemRepository.findByExternalUuid(outputUuid)).thenReturn(Optional.of(output));
    when(materialRepository.findByScwikiUuid(resourceUuid)).thenReturn(Optional.of(agricium));
    when(gameItemRepository.findByExternalUuid(itemUuid)).thenReturn(Optional.of(hadanite));
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncBlueprints();

    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    Blueprint bp = saved.getValue();
    assertSame(output, bp.getOutputItem());
    assertEquals(2, bp.getIngredients().size());

    BlueprintIngredient line0 = bp.getIngredients().get(0);
    assertEquals(BlueprintIngredientKind.RESOURCE, line0.getKind());
    assertSame(agricium, line0.getMaterial());
    assertNull(line0.getGameItem());
    assertEquals(0.36, line0.getQuantityScu());
    assertNull(line0.getQuantityUnits());
    assertEquals(resourceUuid, line0.getWikiResourceUuid());

    BlueprintIngredient line1 = bp.getIngredients().get(1);
    assertEquals(BlueprintIngredientKind.ITEM, line1.getKind());
    assertSame(hadanite, line1.getGameItem());
    assertNull(line1.getMaterial());
    assertEquals(7, line1.getQuantityUnits());
    assertNull(line1.getQuantityScu());
  }

  @Test
  void syncBlueprints_detailWithRequirementGroups_persistsGroupsModifiersIngredientsAndSummary() {
    UUID bpUuid = UUID.randomUUID();
    UUID outputUuid = UUID.randomUUID();
    UUID resourceUuid = UUID.randomUUID();
    UUID itemUuid = UUID.randomUUID();

    ScWikiBlueprintDto listDto =
        new ScWikiBlueprintDto(
            bpUuid,
            "BP",
            outputUuid,
            "Omnisky",
            null,
            540,
            false,
            "4.8",
            2,
            1,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    ScWikiBlueprintModifierDto frameModifier =
        new ScWikiBlueprintModifierDto(
            "health_maxhealth",
            null,
            "Integrity",
            "higher",
            new ScWikiBlueprintQualityRangeDto(0.0, 1000.0),
            new ScWikiBlueprintModifierRangeDto(0.9, 1.1),
            "linear",
            List.of(
                new ScWikiBlueprintModifierSegmentDto(0.0, 500.0, 0.9, 1.0),
                new ScWikiBlueprintModifierSegmentDto(500.0, 1000.0, 1.0, 1.1)));
    ScWikiBlueprintRequirementChildDto frameChild =
        new ScWikiBlueprintRequirementChildDto(
            null, "resource", resourceUuid, "Agricium", null, 0.36, 1, null);
    ScWikiBlueprintRequirementGroupDto frame =
        new ScWikiBlueprintRequirementGroupDto(
            "FRAME", "Frame", "group", 1, List.of(frameModifier), List.of(frameChild));

    ScWikiBlueprintModifierDto emitterModifier =
        new ScWikiBlueprintModifierDto(
            "weapon_damage",
            null,
            "Impact Force",
            "higher",
            new ScWikiBlueprintQualityRangeDto(0.0, 1000.0),
            new ScWikiBlueprintModifierRangeDto(0.95, 1.05),
            "linear",
            null);
    ScWikiBlueprintRequirementChildDto emitterChild =
        new ScWikiBlueprintRequirementChildDto(
            null, "item", itemUuid, "Hadanite", 7, null, 1, null);
    ScWikiBlueprintRequirementGroupDto emitter =
        new ScWikiBlueprintRequirementGroupDto(
            "EMITTER", "Emitter", "group", 1, List.of(emitterModifier), List.of(emitterChild));

    ScWikiBlueprintSummaryPropertyDto summary =
        new ScWikiBlueprintSummaryPropertyDto("weapon_damage", null, "Impact Force", "higher");

    ScWikiBlueprintDto detail =
        new ScWikiBlueprintDto(
            bpUuid,
            "BP",
            outputUuid,
            "Omnisky",
            null,
            540,
            false,
            "4.8",
            2,
            1,
            List.of(),
            List.of(),
            List.of(frame, emitter),
            List.of(summary),
            new ScWikiBlueprintDismantleDto(15, 0.5),
            null,
            null,
            null,
            null);

    Material agricium = material("Agricium");
    GameItem hadanite = gameItem(itemUuid);
    GameItem output = gameItem(outputUuid);
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(listDto)));
    when(scWikiClient.fetchOne(anyString(), any(), eq(ScWikiBlueprintDto.class), eq("blueprint")))
        .thenReturn(detail);
    when(blueprintRepository.findByScwikiUuid(bpUuid)).thenReturn(Optional.empty());
    when(gameItemRepository.findByExternalUuid(outputUuid)).thenReturn(Optional.of(output));
    when(materialRepository.findByScwikiUuid(resourceUuid)).thenReturn(Optional.of(agricium));
    when(gameItemRepository.findByExternalUuid(itemUuid)).thenReturn(Optional.of(hadanite));
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncBlueprints();

    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    Blueprint bp = saved.getValue();

    assertEquals(2, bp.getRequirementGroups().size(), "both slots persisted");
    BlueprintRequirementGroup frameGroup = bp.getRequirementGroups().get(0);
    assertEquals("Frame", frameGroup.getName());
    assertEquals(1, frameGroup.getModifiers().size());
    BlueprintRequirementModifier frameMod = frameGroup.getModifiers().get(0);
    assertEquals("health_maxhealth", frameMod.getPropertyKey());
    assertEquals("Integrity", frameMod.getLabel());
    assertEquals(0.9, frameMod.getModifierAtMinQuality());
    assertEquals(1.1, frameMod.getModifierAtMaxQuality());
    assertEquals(1000.0, frameMod.getQualityMax());
    assertEquals(2, frameMod.getSegments().size(), "stepped-curve segments persisted in order");
    assertEquals(0.0, frameMod.getSegments().get(0).getQualityMin());
    assertEquals(1.0, frameMod.getSegments().get(0).getModifierAtEnd());
    assertEquals(500.0, frameMod.getSegments().get(1).getQualityMin());
    assertEquals(1.1, frameMod.getSegments().get(1).getModifierAtEnd());

    assertEquals(2, bp.getIngredients().size(), "one ingredient per slot child");
    BlueprintIngredient resourceLine = bp.getIngredients().get(0);
    assertEquals(BlueprintIngredientKind.RESOURCE, resourceLine.getKind());
    assertSame(agricium, resourceLine.getMaterial());
    assertSame(frameGroup, resourceLine.getRequirementGroup(), "ingredient linked to its slot");
    assertEquals(1, resourceLine.getMinQuality());
    BlueprintIngredient itemLine = bp.getIngredients().get(1);
    assertEquals(BlueprintIngredientKind.ITEM, itemLine.getKind());
    assertSame(hadanite, itemLine.getGameItem());
    assertSame(bp.getRequirementGroups().get(1), itemLine.getRequirementGroup());

    assertEquals(1, bp.getSummaryProperties().size());
    assertEquals("Impact Force", bp.getSummaryProperties().get(0).getLabel());
    assertEquals(15, bp.getDismantleTimeSeconds());
    assertEquals(0.5, bp.getDismantleEfficiency());
  }

  @Test
  void syncBlueprints_unresolvedIngredient_persistsSnapshotAndEmitsEvent() {
    UUID resourceUuid = UUID.randomUUID();
    ScWikiBlueprintIngredientDto resource =
        new ScWikiBlueprintIngredientDto("Unobtanium", "resource", resourceUuid, null, 1.0, null);
    ScWikiBlueprintDto dto = blueprint(null, List.of(resource), List.of());

    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(blueprintRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());
    when(materialRepository.findByScwikiUuid(resourceUuid)).thenReturn(Optional.empty());
    when(aliasService.resolveMaterialByAlias(any(), any())).thenReturn(null);
    when(materialRepository.findByName("Unobtanium")).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncBlueprints();

    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    BlueprintIngredient line = saved.getValue().getIngredients().get(0);
    assertNull(line.getMaterial(), "unresolved RESOURCE keeps a null material FK");
    assertEquals(
        resourceUuid, line.getWikiResourceUuid(), "raw Wiki uuid is persisted for re-resolve");
    assertEquals("Unobtanium", line.getWikiNameSnapshot());
    verify(syncReportService)
        .logScwikiEvent(
            any(),
            eq(SyncEventType.UNRESOLVED_INGREDIENT),
            eq("blueprint"),
            any(),
            eq("Unobtanium"),
            any());
  }

  @Test
  void syncBlueprints_shrinkingIngredientCount_dropsTrailingLines() {
    UUID bpUuid = UUID.randomUUID();
    Blueprint existing = new Blueprint();
    existing.setScwikiUuid(bpUuid);
    for (int i = 0; i < 3; i++) {
      BlueprintIngredient line = new BlueprintIngredient();
      line.setOrderIndex(i);
      line.setKind(BlueprintIngredientKind.RESOURCE);
      existing.addIngredient(line);
    }
    ScWikiBlueprintIngredientDto one =
        new ScWikiBlueprintIngredientDto("Iron", "resource", UUID.randomUUID(), null, 1.0, null);
    ScWikiBlueprintDto dto =
        new ScWikiBlueprintDto(
            bpUuid,
            "BP",
            null,
            "Out",
            null,
            10,
            false,
            "4.8",
            1,
            0,
            List.of(one),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(blueprintRepository.findByScwikiUuid(bpUuid)).thenReturn(Optional.of(existing));
    when(materialRepository.findByScwikiUuid(any())).thenReturn(Optional.empty());
    when(aliasService.resolveMaterialByAlias(any(), any())).thenReturn(null);
    when(materialRepository.findByName(any())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncBlueprints();

    assertEquals(1, existing.getIngredients().size(), "trailing lines must be dropped");
  }

  @Test
  void syncBlueprints_emptyResponse_skipsOrphanSweep() {
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of()));

    int written = service.syncBlueprints();

    verify(blueprintRepository, never()).markScwikiDeleted(any(), any());
    verify(blueprintRepository, never()).save(any());
    assertEquals(0, written, "an empty Wiki response must report zero upserted blueprints");
    verify(syncReportService).recordSweep(false);
  }

  @Test
  void repeatedBlueprintPayloadsAreAppliedAgainWhenTheirListBodyIsTheSame() {
    ScWikiBlueprintDto dto = blueprint(null, List.of(), List.of());
    Blueprint existing = new Blueprint();
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(blueprintRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.of(existing));
    when(blueprintRepository.save(any(Blueprint.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertEquals(1, service.syncBlueprints());
    existing.setOutputName("stale");
    assertEquals(1, service.syncBlueprints());

    assertEquals(dto.outputName(), existing.getOutputName());
    verify(blueprintRepository, times(2)).save(existing);
    verify(scWikiClient, times(2)).fetchOne(any(), any(), eq(ScWikiBlueprintDto.class), any());
  }

  @Test
  void syncBlueprints_runsOrphanSweep_whenAtLeastOneProcessed() {
    ScWikiBlueprintDto dto = blueprint(null, List.of(), List.of());
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(blueprintRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));
    when(blueprintRepository.markScwikiDeleted(any(), any())).thenReturn(0);

    service.syncBlueprints();

    verify(blueprintRepository).markScwikiDeleted(any(), any());
  }

  private static final String ARMS_KEY = "BP_CRAFT_qrt_specialist_heavy_arms_01_01_13";
  private static final String HELMET_KEY = "BP_CRAFT_qrt_specialist_heavy_helmet_01_01_12";

  @Test
  void syncBlueprints_correctsBothSeededCigMislabels_inOneRun() {
    ScWikiBlueprintDto arms =
        blueprintWithKeyAndName(UUID.randomUUID(), ARMS_KEY, "Antium Helmet Jet");
    ScWikiBlueprintDto helmet =
        blueprintWithKeyAndName(UUID.randomUUID(), HELMET_KEY, "Antium Core Jet");
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(arms, helmet)));
    when(blueprintRepository.findByScwikiUuid(any())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncBlueprints();

    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository, times(2)).save(saved.capture());
    assertEquals(2, saved.getAllValues().size());
    for (Blueprint bp : saved.getAllValues()) {
      if (ARMS_KEY.equals(bp.getScwikiKey())) {
        assertEquals("Antium Arms Maroon", bp.getOutputName());
      } else if (HELMET_KEY.equals(bp.getScwikiKey())) {
        assertEquals("Antium Helmet Jet", bp.getOutputName());
      } else {
        fail("unexpected scwiki_key " + bp.getScwikiKey());
      }
    }
    verify(syncReportService, never())
        .logScwikiEvent(
            any(), eq(SyncEventType.BLUEPRINT_NAME_OVERRIDE_OBSOLETE), any(), any(), any(), any());
  }

  @Test
  void syncBlueprints_correctionIsNormalizationInsensitive() {
    ScWikiBlueprintDto dto =
        blueprintWithKeyAndName(UUID.randomUUID(), ARMS_KEY, "  antium   HELMET jet ");
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(blueprintRepository.findByScwikiUuid(any())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncBlueprints();

    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    assertEquals("Antium Arms Maroon", saved.getValue().getOutputName());
  }

  @Test
  void syncBlueprints_passesThroughOutputName_whenIncomingIsNotTheWrongName() {
    ScWikiBlueprintDto dto =
        blueprintWithKeyAndName(UUID.randomUUID(), ARMS_KEY, "Antium Arms Maroon");
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(blueprintRepository.findByScwikiUuid(any())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncBlueprints();

    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    assertEquals("Antium Arms Maroon", saved.getValue().getOutputName());
  }

  @Test
  void syncBlueprints_passesThroughOutputName_forUnrelatedKey() {
    ScWikiBlueprintDto dto =
        blueprintWithKeyAndName(UUID.randomUUID(), "BP_CRAFT_unrelated_01", "Antium Helmet Jet");
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(blueprintRepository.findByScwikiUuid(any())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncBlueprints();

    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    assertEquals("Antium Helmet Jet", saved.getValue().getOutputName());
    verify(syncReportService, never())
        .logScwikiEvent(
            any(), eq(SyncEventType.BLUEPRINT_NAME_OVERRIDE_OBSOLETE), any(), any(), any(), any());
  }

  @Test
  void syncBlueprints_emitsObsoleteEvent_whenOverrideKeySeenButWrongNameGone() {
    ScWikiBlueprintDto dto =
        blueprintWithKeyAndName(UUID.randomUUID(), ARMS_KEY, "Antium Arms Maroon");
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(blueprintRepository.findByScwikiUuid(any())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncBlueprints();

    verify(syncReportService)
        .logScwikiEvent(
            any(),
            eq(SyncEventType.BLUEPRINT_NAME_OVERRIDE_OBSOLETE),
            eq("blueprint"),
            isNull(),
            eq("Antium Arms Maroon"),
            any());
  }

  @Test
  void syncBlueprints_detailMiss_preservesExistingRequirementGroupsAndSummary() {
    UUID bpUuid = UUID.randomUUID();
    UUID outputUuid = UUID.randomUUID();
    UUID resourceUuid = UUID.randomUUID();
    Blueprint existing = new Blueprint();
    existing.setScwikiUuid(bpUuid);
    BlueprintRequirementGroup existingGroup = new BlueprintRequirementGroup();
    existingGroup.setOrderIndex(0);
    existingGroup.setGroupKey("FRAME");
    existingGroup.setName("Frame");
    existingGroup.setKind("group");
    existingGroup.setRequiredCount(1);
    existing.addRequirementGroup(existingGroup);
    BlueprintSummaryProperty existingSummary = new BlueprintSummaryProperty();
    existingSummary.setOrderIndex(0);
    existingSummary.setPropertyKey("weapon_damage");
    existingSummary.setLabel("Impact Force");
    existingSummary.setBetterWhen("higher");
    existing.addSummaryProperty(existingSummary);

    Material agricium = material("Agricium");
    BlueprintIngredient existingIngredient = new BlueprintIngredient();
    existingIngredient.setOrderIndex(0);
    existingIngredient.setKind(BlueprintIngredientKind.RESOURCE);
    existingIngredient.setMaterial(agricium);
    existingIngredient.setWikiResourceUuid(resourceUuid);
    existingIngredient.setQuantityScu(0.25);
    existingIngredient.setRequirementGroup(existingGroup);
    existing.addIngredient(existingIngredient);

    ScWikiBlueprintIngredientDto resource =
        new ScWikiBlueprintIngredientDto("Agricium", "resource", resourceUuid, null, 0.36, null);
    ScWikiBlueprintDto listDto =
        new ScWikiBlueprintDto(
            bpUuid,
            "BP",
            outputUuid,
            "Omnisky",
            null,
            540,
            false,
            "4.8",
            1,
            0,
            List.of(resource),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(listDto)));
    when(scWikiClient.fetchOne(anyString(), any(), eq(ScWikiBlueprintDto.class), eq("blueprint")))
        .thenReturn(null);
    when(blueprintRepository.findByScwikiUuid(bpUuid)).thenReturn(Optional.of(existing));
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncBlueprints();

    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    Blueprint bp = saved.getValue();
    assertEquals(1, bp.getRequirementGroups().size(), "requirement group must not be wiped");
    assertSame(existingGroup, bp.getRequirementGroups().get(0), "same group object preserved");
    assertEquals("Frame", bp.getRequirementGroups().get(0).getName());
    assertEquals(1, bp.getSummaryProperties().size(), "summary property must not be wiped");
    assertSame(existingSummary, bp.getSummaryProperties().get(0), "same summary object preserved");
    assertEquals("Impact Force", bp.getSummaryProperties().get(0).getLabel());

    assertEquals(1, bp.getIngredients().size(), "loaded recipe graph remains coherent");
    BlueprintIngredient line = bp.getIngredients().get(0);
    assertEquals(BlueprintIngredientKind.RESOURCE, line.getKind());
    assertSame(existingIngredient, line);
    assertSame(existingGroup, line.getRequirementGroup());
    assertSame(agricium, line.getMaterial());
    assertEquals(
        0.25, line.getQuantityScu(), "a flat fallback cannot rewrite a loaded recipe graph");
  }

  @Test
  void syncBlueprints_resolvesResourceViaAliasTable_whenUuidMisses() {
    UUID resourceUuid = UUID.randomUUID();
    ScWikiBlueprintIngredientDto resource =
        new ScWikiBlueprintIngredientDto("Agricium", "resource", resourceUuid, null, 0.36, null);
    ScWikiBlueprintDto dto = blueprint(null, List.of(resource), List.of());

    Material aliasMaterial = material("Agricium");
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(blueprintRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());
    when(materialRepository.findByScwikiUuid(resourceUuid)).thenReturn(Optional.empty());
    when(aliasService.resolveMaterialByAlias(MaterialExternalAliasSource.SCWIKI, "Agricium"))
        .thenReturn(aliasMaterial);
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncBlueprints();

    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    BlueprintIngredient line = saved.getValue().getIngredients().get(0);
    assertSame(aliasMaterial, line.getMaterial(), "resource resolved via the SCWIKI alias table");
    verify(syncReportService, never())
        .logScwikiEvent(any(), eq(SyncEventType.UNRESOLVED_INGREDIENT), any(), any(), any(), any());
  }

  private ScWikiBlueprintDto blueprintWithKeyAndName(UUID uuid, String key, String outputName) {
    return new ScWikiBlueprintDto(
        uuid,
        key,
        null,
        outputName,
        null,
        540,
        false,
        "4.8.0-LIVE",
        0,
        0,
        List.of(),
        List.of(),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private ScWikiBlueprintDto blueprint(
      UUID outputUuid,
      List<ScWikiBlueprintIngredientDto> ingredients,
      List<ScWikiBlueprintIngredientDto> dismantle) {
    return new ScWikiBlueprintDto(
        UUID.randomUUID(),
        "BP_TEST",
        outputUuid,
        "Test Output",
        UUID.randomUUID(),
        540,
        false,
        "4.8.0-LIVE",
        ingredients.size(),
        0,
        ingredients,
        dismantle,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private Material material(String name) {
    Material m = new Material();
    m.setId(UUID.randomUUID());
    m.setName(name);
    return m;
  }

  private GameItem gameItem(UUID externalUuid) {
    GameItem g = new GameItem();
    g.setId(UUID.randomUUID());
    g.setExternalUuid(externalUuid);
    g.setName("Item " + externalUuid);
    return g;
  }

  @Test
  void capturedDetailPreservesResourceAndOreUuidsAndTypedUnlockEvidence() throws IOException {
    ScWikiBlueprintDto dto = capturedDetail();
    when(blueprintRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());

    service.upsertBlueprintWithinTransaction(dto.uuid(), dto, UUID.randomUUID(), Instant.now());

    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    Blueprint bp = saved.getValue();
    BlueprintIngredient resource = bp.getIngredients().getFirst();
    assertEquals(
        UUID.fromString("dc6fbcbb-5990-4ed5-82ee-93152dab7845"), resource.getWikiResourceUuid());
    assertEquals(
        UUID.fromString("fc1ec740-3047-48d8-81f0-396f4c9a90ef"), resource.getWikiOreUuid());
    assertNotEquals(resource.getWikiResourceUuid(), resource.getWikiOreUuid());
    var metadata = bp.getWikiMetadata();
    assertNotNull(metadata);
    assertEquals("Tactical Strike Group Needed", metadata.unlockingMissions().getFirst().title());
    assertEquals(1.0, metadata.unlockingMissions().getFirst().chance());
    assertEquals(3, metadata.aspects().aspects().size());
    assertEquals(540, metadata.tiers().getFirst().craftTimeSeconds());
    assertEquals(
        UUID.fromString("93a49e68-01cf-4d7e-b995-8224466a9982"),
        metadata
            .tiers()
            .getFirst()
            .requirements()
            .children()
            .getFirst()
            .modifiers()
            .getFirst()
            .propertyUuid());
    JsonMapper mapper = JsonMapper.builder().build();
    assertEquals(
        metadata,
        mapper.readValue(
            mapper.writeValueAsString(metadata),
            de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintCatalogueMetadata.class));
  }

  @Test
  void listFallbackDoesNotEraseLoadedGroupsIngredientsOrMetadata() throws IOException {
    ScWikiBlueprintDto detail = capturedDetail();
    Blueprint bp = new Blueprint();
    when(blueprintRepository.findByScwikiUuid(detail.uuid())).thenReturn(Optional.of(bp));
    service.upsertBlueprintWithinTransaction(
        detail.uuid(), detail, UUID.randomUUID(), Instant.now());
    var originalMetadata = bp.getWikiMetadata();
    var originalGroup = bp.getRequirementGroups().getFirst();
    var originalIngredient = bp.getIngredients().getFirst();
    ObjectNode listPayload = JsonMapper.builder().build().valueToTree(detail);
    for (String key :
        List.of(
            "requirement_groups",
            "summary_properties",
            "dismantle",
            "unlocking_missions",
            "unlocking_missions_grouped",
            "aspects",
            "tiers")) {
      listPayload.remove(key);
    }
    ScWikiBlueprintDto list =
        JsonMapper.builder().build().treeToValue(listPayload, ScWikiBlueprintDto.class);

    service.upsertBlueprintWithinTransaction(detail.uuid(), list, UUID.randomUUID(), Instant.now());

    assertSame(originalMetadata, bp.getWikiMetadata());
    assertSame(originalGroup, bp.getRequirementGroups().getFirst());
    assertSame(originalIngredient, bp.getIngredients().getFirst());
    assertEquals(3, bp.getIngredients().size());
  }

  @Test
  void aWikiCraftTimeThatDiffersFromTheP4k_leavesTheP4kValue_andIsReported() throws IOException {
    ScWikiBlueprintDto detail = capturedDetail();
    UUID runId = UUID.randomUUID();
    UUID p4kUuid = UUID.randomUUID();
    Blueprint bp = new Blueprint();
    bp.setP4kUuid(p4kUuid);
    bp.setCraftTimeSeconds(77);
    bp.setP4kCraftTimeSeconds(77);
    when(blueprintRepository.findByScwikiUuid(detail.uuid())).thenReturn(Optional.of(bp));

    service.upsertBlueprintWithinTransaction(detail.uuid(), detail, runId, Instant.now());

    assertEquals(77, bp.getCraftTimeSeconds());
    verify(syncReportService)
        .logP4kDisagreement(
            SyncSourceSystem.SCWIKI,
            runId,
            "blueprint",
            p4kUuid,
            "craft_time_seconds",
            "77",
            "540");
  }

  @Test
  void onAP4kUnknownBlueprint_theWikiWritesTheCraftTime() throws IOException {
    ScWikiBlueprintDto detail = capturedDetail();
    Blueprint bp = new Blueprint();
    bp.setCraftTimeSeconds(77);
    when(blueprintRepository.findByScwikiUuid(detail.uuid())).thenReturn(Optional.of(bp));

    service.upsertBlueprintWithinTransaction(
        detail.uuid(), detail, UUID.randomUUID(), Instant.now());

    assertEquals(540, bp.getCraftTimeSeconds());
    verify(syncReportService, never())
        .logP4kDisagreement(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void aP4kCraftTimeOfNull_isFilledByTheWiki() throws IOException {
    ScWikiBlueprintDto detail = capturedDetail();
    Blueprint bp = new Blueprint();
    bp.setP4kUuid(UUID.randomUUID());
    when(blueprintRepository.findByScwikiUuid(detail.uuid())).thenReturn(Optional.of(bp));

    service.upsertBlueprintWithinTransaction(
        detail.uuid(), detail, UUID.randomUUID(), Instant.now());

    assertEquals(540, bp.getCraftTimeSeconds());
  }

  @Test
  void aRecipeTheP4kWrote_keepsItsLines_andTheWikiFillsOnlyItsGaps() throws IOException {
    ScWikiBlueprintDto detail = capturedDetail();
    Blueprint bp = new Blueprint();
    bp.setP4kUuid(UUID.randomUUID());
    bp.setP4kRecipe(true);
    BlueprintRequirementGroup frame = new BlueprintRequirementGroup();
    frame.setOrderIndex(0);
    frame.setGroupKey("frame");
    bp.addRequirementGroup(frame);
    BlueprintIngredient canonical = new BlueprintIngredient();
    canonical.setKind(BlueprintIngredientKind.RESOURCE);
    canonical.setOrderIndex(0);
    canonical.setQuantityScu(9.0);
    canonical.setRequirementGroup(frame);
    bp.addIngredient(canonical);
    when(blueprintRepository.findByScwikiUuid(detail.uuid())).thenReturn(Optional.of(bp));

    service.upsertBlueprintWithinTransaction(
        detail.uuid(), detail, UUID.randomUUID(), Instant.now());

    assertSame(canonical, bp.getIngredients().getFirst());
    assertEquals(1, bp.getIngredients().size());
    assertEquals(9.0, canonical.getQuantityScu());
    assertSame(frame, bp.getRequirementGroups().getFirst());
    assertNotNull(frame.getName());
    assertEquals(
        UUID.fromString("dc6fbcbb-5990-4ed5-82ee-93152dab7845"), canonical.getWikiResourceUuid());
    assertNotNull(bp.getWikiMetadata());
  }

  @Test
  void aP4kSeededBlueprintWithoutAWikiUuid_isFoundByItsGuid_andTakesTheWikiUuid()
      throws IOException {
    ScWikiBlueprintDto detail = capturedDetail();
    Blueprint seeded = new Blueprint();
    seeded.setP4kUuid(detail.uuid());
    when(blueprintRepository.findByP4kUuid(detail.uuid())).thenReturn(Optional.of(seeded));

    service.upsertBlueprintWithinTransaction(
        detail.uuid(), detail, UUID.randomUUID(), Instant.now());

    assertEquals(detail.uuid(), seeded.getScwikiUuid());
    verify(blueprintRepository).save(seeded);
  }

  @Test
  void failedWritesAreNotCountedAsAppliedAndCanBeReplayed() {
    ScWikiBlueprintDto dto = blueprint(UUID.randomUUID(), List.of(), List.of());
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("blueprints")))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(blueprintRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class)))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("unavailable"))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertEquals(0, service.syncBlueprints());
    verify(syncReportService).recordFailed(1);
    verify(syncReportService, never()).recordApplied(anyLong());

    assertEquals(1, service.syncBlueprints());
    verify(syncReportService).recordApplied(1);
    verify(scWikiClient, times(2)).fetchAllPagesResult(any(), any(), eq("blueprints"));
  }

  private static ScWikiBlueprintDto capturedDetail() throws IOException {
    try (InputStream input =
        ScWikiBlueprintSyncServiceTest.class.getResourceAsStream(
            "/catalogue/scwiki-blueprint-detail.json")) {
      assertNotNull(input);
      JsonMapper mapper = JsonMapper.builder().build();
      return mapper.treeToValue(mapper.readTree(input).path("data"), ScWikiBlueprintDto.class);
    }
  }
}
