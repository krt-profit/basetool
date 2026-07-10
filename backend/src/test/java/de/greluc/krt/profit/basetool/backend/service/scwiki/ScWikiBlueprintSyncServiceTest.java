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

import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiBlueprintDismantleDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiBlueprintDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiBlueprintIngredientDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiBlueprintModifierDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiBlueprintModifierRangeDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiBlueprintQualityRangeDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiBlueprintRequirementChildDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiBlueprintRequirementGroupDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiBlueprintSummaryPropertyDto;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialExternalAliasSource;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintRequirementGroup;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintRequirementModifier;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintSummaryProperty;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintNameNormalizer;
import de.greluc.krt.profit.basetool.backend.service.MaterialExternalAliasService;
import de.greluc.krt.profit.basetool.backend.service.SyncReportService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

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
  private ScWikiBlueprintSyncService service;

  @BeforeEach
  void setUp() {
    properties = new ScWikiProperties();
    properties.setBlueprintSyncEnabled(true);
    service =
        new ScWikiBlueprintSyncService(
            scWikiClient,
            properties,
            blueprintRepository,
            materialRepository,
            aliasService,
            gameItemRepository,
            syncReportService,
            new BlueprintOutputNameOverrides(new BlueprintNameNormalizer()),
            self);
    lenient().when(self.getObject()).thenReturn(service);
    lenient().when(syncReportService.beginRun()).thenReturn(UUID.randomUUID());
  }

  @Test
  void syncBlueprints_isNoOp_whenFeatureFlagOff() {
    properties.setBlueprintSyncEnabled(false);

    service.syncBlueprints();

    verifyNoInteractions(scWikiClient, blueprintRepository);
  }

  @Test
  void syncBlueprints_fallbackList_resolvesResourceToMaterialAndItemToGameItem() {
    // No detail (fetchOne returns null) — exercises the flat-list fallback path.
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
    when(scWikiClient.fetchAllPages(any(), any(), eq("blueprints"))).thenReturn(List.of(dto));
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

    // Minimal list row used only to enumerate the UUID; the detail carries the requirement groups.
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
                new de.greluc.krt.profit.basetool.backend.dto.scwiki
                    .ScWikiBlueprintModifierSegmentDto(0.0, 500.0, 0.9, 1.0),
                new de.greluc.krt.profit.basetool.backend.dto.scwiki
                    .ScWikiBlueprintModifierSegmentDto(500.0, 1000.0, 1.0, 1.1)));
    ScWikiBlueprintRequirementChildDto frameChild =
        new ScWikiBlueprintRequirementChildDto(
            null, "resource", resourceUuid, "Agricium", null, 0.36, 1);
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
        new ScWikiBlueprintRequirementChildDto(null, "item", itemUuid, "Hadanite", 7, null, 1);
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
            new ScWikiBlueprintDismantleDto(15, 0.5));

    Material agricium = material("Agricium");
    GameItem hadanite = gameItem(itemUuid);
    GameItem output = gameItem(outputUuid);
    when(scWikiClient.fetchAllPages(any(), any(), eq("blueprints"))).thenReturn(List.of(listDto));
    when(scWikiClient.fetchOne(anyString(), eq(ScWikiBlueprintDto.class), eq("blueprint")))
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

    when(scWikiClient.fetchAllPages(any(), any(), eq("blueprints"))).thenReturn(List.of(dto));
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
    // Existing blueprint with 3 ingredient lines.
    Blueprint existing = new Blueprint();
    existing.setScwikiUuid(bpUuid);
    for (int i = 0; i < 3; i++) {
      BlueprintIngredient line = new BlueprintIngredient();
      line.setOrderIndex(i);
      line.setKind(BlueprintIngredientKind.RESOURCE);
      existing.addIngredient(line);
    }
    // Incoming DTO has only 1 ingredient (and no requirement groups → fallback path).
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
            null);

    when(scWikiClient.fetchAllPages(any(), any(), eq("blueprints"))).thenReturn(List.of(dto));
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
    when(scWikiClient.fetchAllPages(any(), any(), eq("blueprints"))).thenReturn(List.of());

    int written = service.syncBlueprints();

    verify(blueprintRepository, never()).markScwikiDeleted(any(), any());
    verify(blueprintRepository, never()).save(any());
    // An empty Wiki response upserts no blueprints → 0 items (#1041 item 2, SyncZeroItems).
    assertEquals(0, written, "an empty Wiki response must report zero upserted blueprints");
  }

  @Test
  void syncBlueprints_runsOrphanSweep_whenAtLeastOneProcessed() {
    ScWikiBlueprintDto dto = blueprint(null, List.of(), List.of());
    when(scWikiClient.fetchAllPages(any(), any(), eq("blueprints"))).thenReturn(List.of(dto));
    when(blueprintRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));
    when(blueprintRepository.markScwikiDeleted(any(), any())).thenReturn(0);

    service.syncBlueprints();

    verify(blueprintRepository).markScwikiDeleted(any(), any());
  }

  // ─── #327 curated CIG-mislabel output-name override (REQ-INV-007) ──────────

  private static final String ARMS_KEY = "BP_CRAFT_qrt_specialist_heavy_arms_01_01_13";
  private static final String HELMET_KEY = "BP_CRAFT_qrt_specialist_heavy_helmet_01_01_12";

  @Test
  void syncBlueprints_correctsBothSeededCigMislabels_inOneRun() {
    // Given both confirmed mislabeled blueprints arrive with their known-wrong output names.
    ScWikiBlueprintDto arms =
        blueprintWithKeyAndName(UUID.randomUUID(), ARMS_KEY, "Antium Helmet Jet");
    ScWikiBlueprintDto helmet =
        blueprintWithKeyAndName(UUID.randomUUID(), HELMET_KEY, "Antium Core Jet");
    when(scWikiClient.fetchAllPages(any(), any(), eq("blueprints")))
        .thenReturn(List.of(arms, helmet));
    when(blueprintRepository.findByScwikiUuid(any())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    // When the sync runs.
    service.syncBlueprints();

    // Then each blueprint is persisted with its in-game-correct name, and nothing is reported
    // obsolete (the guards fired).
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
    // Given the wrong name arrives with different case and whitespace.
    ScWikiBlueprintDto dto =
        blueprintWithKeyAndName(UUID.randomUUID(), ARMS_KEY, "  antium   HELMET jet ");
    when(scWikiClient.fetchAllPages(any(), any(), eq("blueprints"))).thenReturn(List.of(dto));
    when(blueprintRepository.findByScwikiUuid(any())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    // When the sync runs.
    service.syncBlueprints();

    // Then the normalizer folds the differences and the correction still applies.
    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    assertEquals("Antium Arms Maroon", saved.getValue().getOutputName());
  }

  @Test
  void syncBlueprints_passesThroughOutputName_whenIncomingIsNotTheWrongName() {
    // Given CIG fixed the name (the feed now sends the in-game-correct name for the same key).
    ScWikiBlueprintDto dto =
        blueprintWithKeyAndName(UUID.randomUUID(), ARMS_KEY, "Antium Arms Maroon");
    when(scWikiClient.fetchAllPages(any(), any(), eq("blueprints"))).thenReturn(List.of(dto));
    when(blueprintRepository.findByScwikiUuid(any())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    // When the sync runs.
    service.syncBlueprints();

    // Then the upstream value is persisted unchanged (the guard did not fire).
    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    assertEquals("Antium Arms Maroon", saved.getValue().getOutputName());
  }

  @Test
  void syncBlueprints_passesThroughOutputName_forUnrelatedKey() {
    // Given an unrelated key whose name happens to equal a wrong name registered under another key.
    ScWikiBlueprintDto dto =
        blueprintWithKeyAndName(UUID.randomUUID(), "BP_CRAFT_unrelated_01", "Antium Helmet Jet");
    when(scWikiClient.fetchAllPages(any(), any(), eq("blueprints"))).thenReturn(List.of(dto));
    when(blueprintRepository.findByScwikiUuid(any())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    // When the sync runs.
    service.syncBlueprints();

    // Then nothing is corrected and no obsolescence event is emitted (the key is not registered).
    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    assertEquals("Antium Helmet Jet", saved.getValue().getOutputName());
    verify(syncReportService, never())
        .logScwikiEvent(
            any(), eq(SyncEventType.BLUEPRINT_NAME_OVERRIDE_OBSOLETE), any(), any(), any(), any());
  }

  @Test
  void syncBlueprints_emitsObsoleteEvent_whenOverrideKeySeenButWrongNameGone() {
    // Given a registered override key is still in the feed but CIG changed its name away from the
    // expected wrong name (here: to the already-correct name) — the guard no longer fires.
    ScWikiBlueprintDto dto =
        blueprintWithKeyAndName(UUID.randomUUID(), ARMS_KEY, "Antium Arms Maroon");
    when(scWikiClient.fetchAllPages(any(), any(), eq("blueprints"))).thenReturn(List.of(dto));
    when(blueprintRepository.findByScwikiUuid(any())).thenReturn(Optional.empty());
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    // When the sync runs.
    service.syncBlueprints();

    // Then a BLUEPRINT_NAME_OVERRIDE_OBSOLETE event is emitted so an operator removes the entry.
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
    // Given an existing blueprint that already carries good stat data (one requirement group + one
    // summary property) captured on a previous successful detail fetch.
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

    // The list row carries the flat ingredients but no requirement_groups; the detail fetch misses
    // (fetchOne -> null), so the upsert must take the flat-list fallback, NOT
    // applyRequirementGraph.
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
            null);

    Material agricium = material("Agricium");
    when(scWikiClient.fetchAllPages(any(), any(), eq("blueprints"))).thenReturn(List.of(listDto));
    when(scWikiClient.fetchOne(anyString(), eq(ScWikiBlueprintDto.class), eq("blueprint")))
        .thenReturn(null);
    when(blueprintRepository.findByScwikiUuid(bpUuid)).thenReturn(Optional.of(existing));
    when(materialRepository.findByScwikiUuid(resourceUuid)).thenReturn(Optional.of(agricium));
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    // When the sync runs against a transient detail miss.
    service.syncBlueprints();

    // Then the previously captured group + summary survive untouched (the fallback must NOT clear
    // them) while the flat ingredient list is reconciled.
    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    Blueprint bp = saved.getValue();
    assertEquals(1, bp.getRequirementGroups().size(), "requirement group must not be wiped");
    assertSame(existingGroup, bp.getRequirementGroups().get(0), "same group object preserved");
    assertEquals("Frame", bp.getRequirementGroups().get(0).getName());
    assertEquals(1, bp.getSummaryProperties().size(), "summary property must not be wiped");
    assertSame(existingSummary, bp.getSummaryProperties().get(0), "same summary object preserved");
    assertEquals("Impact Force", bp.getSummaryProperties().get(0).getLabel());

    assertEquals(1, bp.getIngredients().size(), "flat ingredient list is still reconciled");
    BlueprintIngredient line = bp.getIngredients().get(0);
    assertEquals(BlueprintIngredientKind.RESOURCE, line.getKind());
    assertSame(agricium, line.getMaterial());
  }

  @Test
  void syncBlueprints_resolvesResourceViaAliasTable_whenUuidMisses() {
    // Given a RESOURCE line whose scwiki_uuid does not match any material, but the alias table maps
    // its name to a material — the middle branch of resolveMaterial's uuid -> alias -> name chain.
    UUID resourceUuid = UUID.randomUUID();
    ScWikiBlueprintIngredientDto resource =
        new ScWikiBlueprintIngredientDto("Agricium", "resource", resourceUuid, null, 0.36, null);
    ScWikiBlueprintDto dto = blueprint(null, List.of(resource), List.of());

    Material aliasMaterial = material("Agricium");
    when(scWikiClient.fetchAllPages(any(), any(), eq("blueprints"))).thenReturn(List.of(dto));
    when(blueprintRepository.findByScwikiUuid(dto.uuid())).thenReturn(Optional.empty());
    when(materialRepository.findByScwikiUuid(resourceUuid)).thenReturn(Optional.empty());
    when(aliasService.resolveMaterialByAlias(MaterialExternalAliasSource.SCWIKI, "Agricium"))
        .thenReturn(aliasMaterial);
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    // When the sync runs.
    service.syncBlueprints();

    // Then the ingredient FK is linked via the alias table and no unresolved event is emitted.
    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    BlueprintIngredient line = saved.getValue().getIngredients().get(0);
    assertSame(aliasMaterial, line.getMaterial(), "resource resolved via the SCWIKI alias table");
    verify(syncReportService, never())
        .logScwikiEvent(any(), eq(SyncEventType.UNRESOLVED_INGREDIENT), any(), any(), any(), any());
  }

  // ─── helpers ────────────────────────────────────────────────────────────

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
}
