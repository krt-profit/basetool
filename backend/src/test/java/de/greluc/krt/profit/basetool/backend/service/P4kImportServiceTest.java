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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.dto.P4kImportResultDto;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.model.scwiki.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.service.scwiki.BlueprintOutputNameOverrides;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure-Mockito unit tests for {@link P4kImportService}: no Spring context and no database. The five
 * repositories and {@link SyncReportService} are mocked; a real Jackson 3 {@link JsonMapper} parses
 * the synthetic catalogs. Covers the load-bearing reconciliation paths: a GUID match enriches
 * fill-if-null, the {@code class_name} fallback backfills a null {@code external_uuid}, a non-null
 * differing {@code external_uuid} is kept (conflict reported) while {@code p4k_uuid} is still
 * stamped, and an existing unresolved blueprint ingredient is resolved by its stored Wiki UUID.
 */
@ExtendWith(MockitoExtension.class)
class P4kImportServiceTest {

  @Mock private GameItemRepository gameItemRepository;
  @Mock private ShipTypeRepository shipTypeRepository;
  @Mock private ManufacturerRepository manufacturerRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private BlueprintRepository blueprintRepository;
  @Mock private SyncReportService syncReportService;

  private P4kImportService service;

  @BeforeEach
  void setUp() {
    service =
        new P4kImportService(
            JsonMapper.builder().build(),
            gameItemRepository,
            shipTypeRepository,
            manufacturerRepository,
            materialRepository,
            blueprintRepository,
            syncReportService,
            new BlueprintOutputNameOverrides(new BlueprintNameNormalizer()));
    lenient().when(syncReportService.beginRun()).thenReturn(UUID.randomUUID());
  }

  private static byte[] upload(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }

  // ─────────────────────────────────────────────────────── GUID match enrich ──

  @Test
  void item_matchedByGuid_enrichesFillIfNullAndStampsP4k() {
    UUID guid = UUID.randomUUID();
    GameItem existing = new GameItem();
    existing.setName("Arclight");
    existing.setExternalUuid(guid); // already has the same UUID -> no backfill, no conflict
    when(gameItemRepository.findByExternalUuid(guid)).thenReturn(Optional.of(existing));

    String json =
        "{\"items\":[{\"guid\":\""
            + guid
            + "\",\"className\":\"wpn_arclight\",\"name\":\"Arclight\",\"mass\":3.5,"
            + "\"desc\":\"A pistol.\",\"descDe\":\"Eine Pistole.\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), false);

    assertEquals(1, result.items().matched());
    assertEquals(0, result.items().uuidBackfilled());
    assertEquals(0, result.items().uuidConflicts());
    assertEquals(1, result.items().enriched());
    assertEquals(0, result.items().unmatched());

    // Fill-if-null enrichment applied.
    assertEquals("wpn_arclight", existing.getClassName());
    assertEquals(3.5, existing.getMass());
    assertEquals("A pistol.", existing.getDescriptionEn());
    assertEquals("Eine Pistole.", existing.getDescriptionDe());

    // P4K lane stamped; existing UUID untouched.
    assertEquals(guid, existing.getP4kUuid());
    assertNotNull(existing.getP4kSyncedAt());
    assertEquals(guid, existing.getExternalUuid());

    // Same-UUID match emits neither a backfill nor a conflict event.
    verify(syncReportService, never())
        .logP4kEvent(any(), eq(SyncEventType.LINKED_VIA_NAME), any(), any(), any(), any());
    verify(syncReportService, never())
        .logP4kEvent(any(), eq(SyncEventType.BACKFILL_AMBIGUOUS), any(), any(), any(), any());
    verify(syncReportService).pruneRuns(SyncSourceSystem.P4K);
  }

  @Test
  void item_existingNonNullValuesAreNeverOverwritten() {
    UUID guid = UUID.randomUUID();
    GameItem existing = new GameItem();
    existing.setName("Arclight");
    existing.setExternalUuid(guid);
    existing.setMass(9.9);
    existing.setDescriptionEn("Original.");
    when(gameItemRepository.findByExternalUuid(guid)).thenReturn(Optional.of(existing));

    String json =
        "{\"items\":[{\"guid\":\""
            + guid
            + "\",\"name\":\"Arclight\",\"mass\":3.5,\"desc\":\"New text.\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), false);

    // No fill-if-null write happened because the fields were already populated.
    assertEquals(0, result.items().enriched());
    assertEquals(9.9, existing.getMass());
    assertEquals("Original.", existing.getDescriptionEn());
  }

  // ────────────────────────────────── class_name fallback + external_uuid backfill ──

  @Test
  void item_matchedByClassName_backfillsNullExternalUuid() {
    UUID guid = UUID.randomUUID();
    GameItem existing = new GameItem();
    existing.setName("Hornet");
    existing.setExternalUuid(null); // uuid-less row -> eligible for backfill
    when(gameItemRepository.findByExternalUuid(guid)).thenReturn(Optional.empty());
    when(gameItemRepository.findByClassNameIgnoreCase("ship_hornet")).thenReturn(List.of(existing));

    String json =
        "{\"items\":[{\"guid\":\""
            + guid
            + "\",\"className\":\"ship_hornet\",\"name\":\"Hornet\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), false);

    assertEquals(1, result.items().matched());
    assertEquals(1, result.items().uuidBackfilled());
    assertEquals(0, result.items().uuidConflicts());
    assertEquals(0, result.items().unmatched());

    // external_uuid backfilled from the P4K GUID; p4k lane stamped.
    assertEquals(guid, existing.getExternalUuid());
    assertEquals(guid, existing.getP4kUuid());
    assertNotNull(existing.getP4kSyncedAt());

    verify(syncReportService)
        .logP4kEvent(
            any(),
            eq(SyncEventType.LINKED_VIA_NAME),
            eq("game_item"),
            eq(guid),
            eq("Hornet"),
            any());
  }

  @Test
  void item_classNameMatchesMultipleRows_isAmbiguousAndUnmatched() {
    UUID guid = UUID.randomUUID();
    GameItem a = new GameItem();
    a.setName("Dup A");
    GameItem b = new GameItem();
    b.setName("Dup B");
    when(gameItemRepository.findByExternalUuid(guid)).thenReturn(Optional.empty());
    when(gameItemRepository.findByClassNameIgnoreCase("dup_class")).thenReturn(List.of(a, b));
    // Name fallback also misses (return empty so the ambiguous class match is the only signal).
    lenient().when(gameItemRepository.findByNameIgnoreCase(any())).thenReturn(List.of());

    String json =
        "{\"items\":[{\"guid\":\""
            + guid
            + "\",\"className\":\"dup_class\",\"name\":\"No Name Match\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), false);

    assertEquals(0, result.items().matched());
    assertEquals(1, result.items().unmatched());
    assertNull(a.getP4kSyncedAt());
    assertNull(b.getP4kSyncedAt());
  }

  @Test
  void item_resolutionPrefersUuidMatchOverClassNameFallback() {
    UUID guid = UUID.randomUUID();
    GameItem classNameRow = new GameItem();
    classNameRow.setName("Hornet");
    classNameRow.setExternalUuid(null);
    GameItem uuidRow = new GameItem();
    uuidRow.setName("Holds The Guid");
    uuidRow.setExternalUuid(guid);
    // The UUID match wins before the class_name fallback is ever consulted.
    when(gameItemRepository.findByExternalUuid(guid)).thenReturn(Optional.of(uuidRow));

    String json =
        "{\"items\":[{\"guid\":\""
            + guid
            + "\",\"className\":\"ship_hornet\",\"name\":\"Hornet\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), false);

    // The UUID-keyed row is the match (already holds the GUID): no backfill, no conflict, p4k lane
    // stamped on it; the class_name candidate is never touched.
    assertEquals(1, result.items().matched());
    assertEquals(0, result.items().uuidBackfilled());
    assertEquals(0, result.items().uuidConflicts());
    assertEquals(guid, uuidRow.getP4kUuid());
    assertNotNull(uuidRow.getP4kSyncedAt());
    assertNull(classNameRow.getP4kSyncedAt());
    verify(gameItemRepository, never()).findByClassNameIgnoreCase(any());
  }

  // ──────────────────────────────── UUID conflict: keep both + report ──

  @Test
  void item_conflictingExternalUuid_isKeptAndReportedButP4kUuidStamped() {
    UUID p4kGuid = UUID.randomUUID();
    UUID existingUuid = UUID.randomUUID(); // different, non-null -> conflict
    GameItem existing = new GameItem();
    existing.setName("Gladius");
    existing.setExternalUuid(existingUuid);
    // No row matches the P4K GUID; class_name resolves the (differently-keyed) row.
    when(gameItemRepository.findByExternalUuid(p4kGuid)).thenReturn(Optional.empty());
    when(gameItemRepository.findByClassNameIgnoreCase("ship_gladius"))
        .thenReturn(List.of(existing));

    String json =
        "{\"items\":[{\"guid\":\""
            + p4kGuid
            + "\",\"className\":\"ship_gladius\",\"name\":\"Gladius\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), false);

    assertEquals(1, result.items().matched());
    assertEquals(0, result.items().uuidBackfilled());
    assertEquals(1, result.items().uuidConflicts());

    // Existing canonical UUID kept; P4K GUID recorded only in p4k_uuid.
    assertEquals(existingUuid, existing.getExternalUuid());
    assertEquals(p4kGuid, existing.getP4kUuid());
    assertNotNull(existing.getP4kSyncedAt());

    verify(syncReportService)
        .logP4kEvent(
            any(),
            eq(SyncEventType.BACKFILL_AMBIGUOUS),
            eq("game_item"),
            eq(p4kGuid),
            eq("Gladius"),
            any());
    verify(syncReportService, never())
        .logP4kEvent(any(), eq(SyncEventType.LINKED_VIA_NAME), any(), any(), any(), any());
  }

  // ──────────────────────────────── manufacturer index feeds item linking ──

  @Test
  void item_manufacturerLinkedViaManufacturerGuidIndex() {
    UUID mfgGuid = UUID.randomUUID();
    UUID itemGuid = UUID.randomUUID();
    Manufacturer mfg = new Manufacturer();
    mfg.setName("Aegis Dynamics");
    mfg.setAbbreviation("AEGS");
    mfg.setScwikiUuid(mfgGuid);
    when(manufacturerRepository.findByScwikiUuid(mfgGuid)).thenReturn(Optional.of(mfg));

    GameItem item = new GameItem();
    item.setName("Gladius");
    item.setExternalUuid(itemGuid);
    when(gameItemRepository.findByExternalUuid(itemGuid)).thenReturn(Optional.of(item));

    String json =
        "{\"manufacturers\":[{\"guid\":\""
            + mfgGuid
            + "\",\"code\":\"AEGS\",\"name\":\"Aegis Dynamics\"}],"
            + "\"items\":[{\"guid\":\""
            + itemGuid
            + "\",\"name\":\"Gladius\",\"manufacturerGuid\":\""
            + mfgGuid
            + "\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), false);

    assertEquals(1, result.manufacturers().matched());
    assertEquals(1, result.items().matched());
    // The item's null manufacturer was filled from the manufacturer GUID index.
    assertEquals(mfg, item.getManufacturer());
  }

  // ──────────────────────────────── blueprint ingredient resolution ──

  @Test
  void blueprint_resolvesUnresolvedResourceIngredientByWikiUuid() {
    UUID bpGuid = UUID.randomUUID();
    UUID resourceUuid = UUID.randomUUID();

    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(bpGuid);
    BlueprintIngredient ingredient = new BlueprintIngredient();
    ingredient.setKind(BlueprintIngredientKind.RESOURCE);
    ingredient.setOrderIndex(0);
    ingredient.setWikiResourceUuid(resourceUuid);
    ingredient.setMaterial(null); // unresolved
    blueprint.addIngredient(ingredient);

    when(blueprintRepository.findByScwikiUuid(bpGuid)).thenReturn(Optional.of(blueprint));

    Material material = new Material();
    material.setName("Agricium");
    material.setScwikiUuid(resourceUuid);
    when(materialRepository.findByScwikiUuid(resourceUuid)).thenReturn(Optional.of(material));

    String json =
        "{\"blueprints\":[{\"guid\":\""
            + bpGuid
            + "\",\"key\":\"BP_CRAFT_TEST\",\"craftTimeSeconds\":120}]}";

    P4kImportResultDto result = service.applyImport(upload(json), false);

    assertEquals(1, result.blueprints().matched());
    assertEquals(1, result.ingredientsResolved());
    // The previously-unresolved RESOURCE line now points at the material.
    assertEquals(material, blueprint.getIngredients().get(0).getMaterial());
    // Scalars enriched fill-if-null.
    assertEquals("BP_CRAFT_TEST", blueprint.getScwikiKey());
    assertEquals(120, blueprint.getCraftTimeSeconds());
  }

  @Test
  void blueprint_alreadyResolvedIngredientIsNotRecounted() {
    UUID bpGuid = UUID.randomUUID();
    UUID resourceUuid = UUID.randomUUID();

    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(bpGuid);
    BlueprintIngredient ingredient = new BlueprintIngredient();
    ingredient.setKind(BlueprintIngredientKind.RESOURCE);
    ingredient.setOrderIndex(0);
    ingredient.setWikiResourceUuid(resourceUuid);
    ingredient.setMaterial(new Material()); // already resolved
    blueprint.addIngredient(ingredient);

    when(blueprintRepository.findByScwikiUuid(bpGuid)).thenReturn(Optional.of(blueprint));

    String json = "{\"blueprints\":[{\"guid\":\"" + bpGuid + "\",\"key\":\"BP_CRAFT_TEST\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), false);

    assertEquals(0, result.ingredientsResolved());
    // The resolver never queried the material repo for an already-resolved line.
    verify(materialRepository, never()).findByScwikiUuid(resourceUuid);
  }

  @Test
  void blueprint_seedPathAppliesCigMislabelOutputNameOverride() {
    // Given an unmatched, seedable blueprint whose produced item carries the CIG-mislabeled name
    // (#327): the seed path writes output_name from the produced item, so it must apply the same
    // guarded override the SC Wiki sync uses. Covers REQ-INV-007 (P4K consistency wiring).
    UUID bpGuid = UUID.randomUUID();
    UUID producedGuid = UUID.randomUUID();
    GameItem produced = new GameItem();
    produced.setName("Antium Core Jet"); // the known-wrong name for the helmet blueprint key
    produced.setExternalUuid(producedGuid);
    when(gameItemRepository.findByExternalUuid(producedGuid)).thenReturn(Optional.of(produced));
    when(blueprintRepository.save(any(Blueprint.class))).thenAnswer(inv -> inv.getArgument(0));

    String json =
        "{\"blueprints\":[{\"guid\":\""
            + bpGuid
            + "\",\"key\":\"BP_CRAFT_qrt_specialist_heavy_helmet_01_01_12\",\"producedItemGuid\":\""
            + producedGuid
            + "\"}]}";

    // When the catalog is applied with seeding enabled.
    P4kImportResultDto result = service.applyImport(upload(json), true);

    // Then the seeded blueprint stores the in-game-correct name, not the produced item's wrong
    // name.
    assertEquals(1, result.blueprints().created());
    ArgumentCaptor<Blueprint> saved = ArgumentCaptor.forClass(Blueprint.class);
    verify(blueprintRepository).save(saved.capture());
    assertEquals("Antium Helmet Jet", saved.getValue().getOutputName());
  }

  // ──────────────────────────────── preview makes no writes ──

  @Test
  void preview_computesActionsWithoutWritingOrAuditing() {
    UUID guid = UUID.randomUUID();
    GameItem existing = new GameItem();
    existing.setName("Hornet");
    existing.setExternalUuid(null);
    when(gameItemRepository.findByExternalUuid(guid)).thenReturn(Optional.empty());
    when(gameItemRepository.findByClassNameIgnoreCase("ship_hornet")).thenReturn(List.of(existing));

    String json =
        "{\"items\":[{\"guid\":\""
            + guid
            + "\",\"className\":\"ship_hornet\",\"name\":\"Hornet\",\"desc\":\"x\"}]}";

    P4kImportResultDto result = service.previewImport(upload(json));

    // Counts are computed as if applying...
    assertEquals(1, result.items().matched());
    assertEquals(1, result.items().uuidBackfilled());
    assertEquals(1, result.items().enriched());
    // ...but nothing was written and no run was started.
    assertNull(existing.getExternalUuid());
    assertNull(existing.getP4kSyncedAt());
    assertNull(existing.getDescriptionEn());
    verify(syncReportService, never()).beginRun();
    verify(syncReportService, never()).logP4kEvent(any(), any(), any(), any(), any(), any());
    verify(syncReportService, never()).pruneRuns(any());
  }

  // ──────────────────────────────── seeding new rows (opt-in) ──

  @Test
  void item_unmatchedSeedableRow_isCreatedWhenSeedingOn() {
    UUID guid = UUID.randomUUID();
    when(gameItemRepository.findByExternalUuid(guid)).thenReturn(Optional.empty());
    when(gameItemRepository.findByClassNameIgnoreCase("wpn_newgun")).thenReturn(List.of());
    when(gameItemRepository.findByNameIgnoreCase("Brand New Gun")).thenReturn(List.of());

    String json =
        "{\"items\":[{\"guid\":\""
            + guid
            + "\",\"className\":\"wpn_newgun\",\"name\":\"Brand New Gun\",\"mass\":2.0,"
            + "\"desc\":\"Fresh.\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), true);

    assertEquals(0, result.items().matched());
    assertEquals(1, result.items().created());
    assertEquals(0, result.items().unmatched());
    assertTrue(result.seedingEnabled());

    ArgumentCaptor<GameItem> captor = ArgumentCaptor.forClass(GameItem.class);
    verify(gameItemRepository).save(captor.capture());
    GameItem seeded = captor.getValue();
    assertEquals("Brand New Gun", seeded.getName());
    assertEquals(guid, seeded.getExternalUuid());
    assertEquals(guid, seeded.getP4kUuid());
    assertEquals(GameItemSourceSystem.P4K, seeded.getSourceSystems());
    assertEquals(2.0, seeded.getMass());
    assertNotNull(seeded.getP4kSyncedAt());
    verify(syncReportService)
        .logP4kEvent(
            any(),
            eq(SyncEventType.CREATED_FROM_P4K),
            eq("game_item"),
            eq(guid),
            eq("Brand New Gun"),
            any());
  }

  @Test
  void item_unmatchedSeedableRow_staysUnmatchedWhenSeedingOff() {
    UUID guid = UUID.randomUUID();
    when(gameItemRepository.findByExternalUuid(guid)).thenReturn(Optional.empty());
    when(gameItemRepository.findByClassNameIgnoreCase("wpn_newgun")).thenReturn(List.of());
    when(gameItemRepository.findByNameIgnoreCase("Brand New Gun")).thenReturn(List.of());

    String json =
        "{\"items\":[{\"guid\":\""
            + guid
            + "\",\"className\":\"wpn_newgun\",\"name\":\"Brand New Gun\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), false);

    assertEquals(0, result.items().created());
    assertEquals(1, result.items().unmatched());
    verify(gameItemRepository, never()).save(any());
  }

  @Test
  void item_namelessEngineRecord_isNeverSeeded() {
    UUID guid = UUID.randomUUID();
    when(gameItemRepository.findByExternalUuid(guid)).thenReturn(Optional.empty());
    when(gameItemRepository.findByClassNameIgnoreCase("dummy_internal")).thenReturn(List.of());

    // No localized name (engine-only record): the export leaves name null -> never seeded.
    String json = "{\"items\":[{\"guid\":\"" + guid + "\",\"className\":\"dummy_internal\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), true);

    assertEquals(0, result.items().created());
    assertEquals(1, result.items().unmatched());
    verify(gameItemRepository, never()).save(any());
  }

  @Test
  void item_devTokenClassName_isNeverSeeded() {
    UUID guid = UUID.randomUUID();
    when(gameItemRepository.findByExternalUuid(guid)).thenReturn(Optional.empty());
    when(gameItemRepository.findByClassNameIgnoreCase("test_weapon")).thenReturn(List.of());
    when(gameItemRepository.findByNameIgnoreCase("Looks Real")).thenReturn(List.of());

    // Real-looking name but the class_name carries a 'test' dev token -> filtered out.
    String json =
        "{\"items\":[{\"guid\":\""
            + guid
            + "\",\"className\":\"test_weapon\",\"name\":\"Looks Real\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), true);

    assertEquals(0, result.items().created());
    assertEquals(1, result.items().unmatched());
    verify(gameItemRepository, never()).save(any());
  }

  @Test
  void ship_unmatchedSeedable_isCreatedAndGuardsUniqueName() {
    UUID guid = UUID.randomUUID();
    when(shipTypeRepository.findByExternalUuid(guid)).thenReturn(Optional.empty());
    when(shipTypeRepository.findByClassNameIgnoreCase("ship_new")).thenReturn(List.of());
    when(shipTypeRepository.findByNameIgnoreCase("New Ship")).thenReturn(Optional.empty());

    String json =
        "{\"ships\":[{\"guid\":\""
            + guid
            + "\",\"className\":\"ship_new\",\"name\":\"New Ship\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), true);

    assertEquals(1, result.ships().created());
    ArgumentCaptor<ShipType> captor = ArgumentCaptor.forClass(ShipType.class);
    verify(shipTypeRepository).save(captor.capture());
    ShipType seeded = captor.getValue();
    assertEquals("New Ship", seeded.getName());
    assertEquals(guid, seeded.getExternalUuid());
    assertEquals(GameItemSourceSystem.P4K, seeded.getSourceSystems());
  }

  @Test
  void preview_reportsSeedPotentialWithoutWriting() {
    UUID guid = UUID.randomUUID();
    when(gameItemRepository.findByExternalUuid(guid)).thenReturn(Optional.empty());
    when(gameItemRepository.findByClassNameIgnoreCase("wpn_newgun")).thenReturn(List.of());
    when(gameItemRepository.findByNameIgnoreCase("Brand New Gun")).thenReturn(List.of());

    String json =
        "{\"items\":[{\"guid\":\""
            + guid
            + "\",\"className\":\"wpn_newgun\",\"name\":\"Brand New Gun\"}]}";

    P4kImportResultDto result = service.previewImport(upload(json));

    assertTrue(result.seedingEnabled());
    assertEquals(1, result.items().created()); // would create
    assertEquals(0, result.items().unmatched());
    verify(gameItemRepository, never()).save(any());
    verify(syncReportService, never()).beginRun();
  }

  @Test
  void preview_matchedItemLinkingASeededManufacturer_countsEnrichmentLikeApply() {
    // Parity guarantee: a preview that would seed a manufacturer indexes it in the dry run too, so
    // a
    // matched item linking that manufacturer is counted as enriched exactly as the apply would
    // report — without writing anything. Regression for the dry-run byGuid registration.
    UUID mfgGuid = UUID.randomUUID();
    UUID itemGuid = UUID.randomUUID();

    // Manufacturer is unmatched on every key -> the run would seed it.
    when(manufacturerRepository.findByScwikiUuid(mfgGuid)).thenReturn(Optional.empty());
    when(manufacturerRepository.findByNameIgnoreCase("Aegis Dynamics"))
        .thenReturn(Optional.empty());
    when(manufacturerRepository.findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc("AEGS"))
        .thenReturn(Optional.empty());

    // Item is an existing GUID match with no manufacturer yet.
    GameItem existing = new GameItem();
    existing.setName("Gladius");
    existing.setExternalUuid(itemGuid);
    when(gameItemRepository.findByExternalUuid(itemGuid)).thenReturn(Optional.of(existing));

    String json =
        "{\"manufacturers\":[{\"guid\":\""
            + mfgGuid
            + "\",\"code\":\"AEGS\",\"name\":\"Aegis Dynamics\"}],"
            + "\"items\":[{\"guid\":\""
            + itemGuid
            + "\",\"name\":\"Gladius\",\"manufacturerGuid\":\""
            + mfgGuid
            + "\"}]}";

    P4kImportResultDto result = service.previewImport(upload(json));

    assertEquals(1, result.manufacturers().created());
    assertEquals(1, result.items().matched());
    // The would-be-seeded manufacturer is indexed in the dry run, so the item's manufacturer fill
    // is
    // counted as enrichment — matching what the apply would report.
    assertEquals(1, result.items().enriched());
    // ...but nothing is written and no run is started.
    assertNull(existing.getManufacturer());
    verify(manufacturerRepository, never()).save(any());
    verify(syncReportService, never()).beginRun();
  }

  // ──────────────────────────────── bad input ──

  @Test
  void emptyFile_throwsBadRequest() {
    org.junit.jupiter.api.Assertions.assertThrows(
        de.greluc.krt.profit.basetool.backend.exception.BadRequestException.class,
        () -> service.previewImport(new byte[0]));
  }

  @Test
  void malformedJson_throwsBadRequest() {
    // Truncated / non-JSON body -> the Jackson parse fails -> surfaced as HTTP 400, not a 500.
    org.junit.jupiter.api.Assertions.assertThrows(
        de.greluc.krt.profit.basetool.backend.exception.BadRequestException.class,
        () -> service.previewImport("{ not json ".getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void nonObjectJson_throwsBadRequest() {
    // Syntactically valid JSON but not a catalog object (an array) -> rejected as 400.
    org.junit.jupiter.api.Assertions.assertThrows(
        de.greluc.krt.profit.basetool.backend.exception.BadRequestException.class,
        () -> service.previewImport(upload("[1,2,3]")));
  }

  // ──────────────────────────────── UNIQUE-collision guard on backfill ──

  @Test
  void item_backfillSkippedWhenAnotherRowAlreadyHoldsTheGuid() {
    UUID guid = UUID.randomUUID();
    GameItem matched = new GameItem();
    matched.setName("Hornet");
    matched.setExternalUuid(null); // uuid-less -> normally eligible for backfill
    GameItem otherHolder = new GameItem();
    otherHolder.setName("Already Holds The Guid");
    otherHolder.setExternalUuid(guid);
    // First lookup (resolution) misses so the class_name fallback matches; the second lookup (the
    // alreadyClaimed guard inside the backfill) finds the other row that already owns the GUID.
    when(gameItemRepository.findByExternalUuid(guid))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(otherHolder));
    when(gameItemRepository.findByClassNameIgnoreCase("ship_hornet")).thenReturn(List.of(matched));

    String json =
        "{\"items\":[{\"guid\":\""
            + guid
            + "\",\"className\":\"ship_hornet\",\"name\":\"Hornet\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), false);

    assertEquals(1, result.items().matched());
    assertEquals(0, result.items().uuidBackfilled()); // guard prevented the UNIQUE collision
    assertEquals(0, result.items().uuidConflicts());

    // The matched row's external_uuid is left null (the GUID belongs to another row); the P4K lane
    // is still stamped so the observation stays auditable.
    assertNull(matched.getExternalUuid());
    assertEquals(guid, matched.getP4kUuid());
    assertNotNull(matched.getP4kSyncedAt());

    // No name/slug backfill event is logged when the backfill is skipped.
    verify(syncReportService, never())
        .logP4kEvent(any(), eq(SyncEventType.LINKED_VIA_NAME), any(), any(), any(), any());
  }

  // ──────────────────────────────── seeded commodity stays invisible ──

  @Test
  void commodity_unmatchedSeedable_isCreatedInvisibleForReview() {
    UUID guid = UUID.randomUUID();
    when(materialRepository.findByScwikiUuid(guid)).thenReturn(Optional.empty());
    when(materialRepository.findByNameIgnoreCase("Quantanium")).thenReturn(Optional.empty());

    String json =
        "{\"commodities\":[{\"guid\":\""
            + guid
            + "\",\"name\":\"Quantanium\",\"desc\":\"Volatile ore.\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), true);

    assertEquals(1, result.commodities().created());

    ArgumentCaptor<Material> captor = ArgumentCaptor.forClass(Material.class);
    verify(materialRepository).save(captor.capture());
    Material seeded = captor.getValue();
    assertEquals("Quantanium", seeded.getName());
    // Seeded commodities are inserted invisible so they stay out of trading flows until an admin
    // reviews them (mirrors the SC-Wiki commodity sync).
    assertEquals(Boolean.FALSE, seeded.getIsVisible());
    assertEquals(MaterialSourceSystem.P4K, seeded.getSourceSystems());
    assertEquals(guid, seeded.getScwikiUuid());
    assertEquals(guid, seeded.getP4kUuid());
    assertNotNull(seeded.getP4kSyncedAt());
    verify(syncReportService)
        .logP4kEvent(
            any(),
            eq(SyncEventType.CREATED_FROM_P4K),
            eq("material"),
            eq(guid),
            eq("Quantanium"),
            any());
  }

  // ──────────────────────────────── blueprint ITEM-ingredient resolution ──

  @Test
  void blueprint_resolvesUnresolvedItemIngredientByWikiItemUuid() {
    // A matched blueprint carries an ITEM line whose component game item was imported only after
    // the blueprint: the still-unresolved line (game_item == null, wiki_item_uuid set) must be
    // re-linked via gameItemRepository.findByExternalUuid and counted in ingredientsResolved. This
    // is the ITEM twin of the RESOURCE branch and guards against a copy-paste swap to the wrong
    // repository / UUID field.
    UUID bpGuid = UUID.randomUUID();
    UUID itemUuid = UUID.randomUUID();

    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(bpGuid);
    BlueprintIngredient ingredient = new BlueprintIngredient();
    ingredient.setKind(BlueprintIngredientKind.ITEM);
    ingredient.setOrderIndex(0);
    ingredient.setWikiItemUuid(itemUuid);
    ingredient.setGameItem(null); // unresolved ITEM line
    blueprint.addIngredient(ingredient);

    when(blueprintRepository.findByScwikiUuid(bpGuid)).thenReturn(Optional.of(blueprint));

    GameItem component = new GameItem();
    component.setName("Ballistic Gatling");
    component.setExternalUuid(itemUuid);
    when(gameItemRepository.findByExternalUuid(itemUuid)).thenReturn(Optional.of(component));

    String json = "{\"blueprints\":[{\"guid\":\"" + bpGuid + "\",\"key\":\"BP_CRAFT_ITEM\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), false);

    assertEquals(1, result.blueprints().matched());
    assertEquals(1, result.ingredientsResolved());
    // The previously-unresolved ITEM line now points at the resolved component game item.
    assertEquals(component, blueprint.getIngredients().get(0).getGameItem());
  }

  @Test
  void blueprint_unresolvedItemIngredient_staysNullWhenComponentAbsent() {
    // Negative twin: the component game item has not been imported yet, so the lookup misses and
    // the line is neither counted nor linked (game_item stays null) — no over-count, no mislink.
    UUID bpGuid = UUID.randomUUID();
    UUID itemUuid = UUID.randomUUID();

    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(bpGuid);
    BlueprintIngredient ingredient = new BlueprintIngredient();
    ingredient.setKind(BlueprintIngredientKind.ITEM);
    ingredient.setOrderIndex(0);
    ingredient.setWikiItemUuid(itemUuid);
    ingredient.setGameItem(null);
    blueprint.addIngredient(ingredient);

    when(blueprintRepository.findByScwikiUuid(bpGuid)).thenReturn(Optional.of(blueprint));
    when(gameItemRepository.findByExternalUuid(itemUuid)).thenReturn(Optional.empty());

    String json = "{\"blueprints\":[{\"guid\":\"" + bpGuid + "\",\"key\":\"BP_CRAFT_ITEM\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), false);

    assertEquals(1, result.blueprints().matched());
    assertEquals(0, result.ingredientsResolved());
    // No FK link established when the component lookup finds nothing.
    assertNull(blueprint.getIngredients().get(0).getGameItem());
  }

  // ──────────────────────────────── dev-token filter: substring is not a word ──

  @Test
  void item_seedableWhenDevTokenIsOnlyASubstring() {
    // 'latest_rifle' merely embeds the 'test' token as a substring — the delimited-word guard in
    // containsToken must NOT classify it as a dev asset (unlike a naive String.contains), so
    // legitimate player content whose engine identifier embeds a token stays seedable.
    UUID guid = UUID.randomUUID();
    when(gameItemRepository.findByExternalUuid(guid)).thenReturn(Optional.empty());
    when(gameItemRepository.findByClassNameIgnoreCase("latest_rifle")).thenReturn(List.of());
    when(gameItemRepository.findByNameIgnoreCase("Latest Rifle")).thenReturn(List.of());

    String json =
        "{\"items\":[{\"guid\":\""
            + guid
            + "\",\"className\":\"latest_rifle\",\"name\":\"Latest Rifle\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), true);

    assertEquals(1, result.items().created());
    assertEquals(0, result.items().unmatched());

    ArgumentCaptor<GameItem> captor = ArgumentCaptor.forClass(GameItem.class);
    verify(gameItemRepository).save(captor.capture());
    assertEquals("Latest Rifle", captor.getValue().getName());
    assertEquals(guid, captor.getValue().getExternalUuid());
  }

  // ──────────────────────────────── malformed GUID isolates one record ──

  @Test
  void item_unparseableGuid_isUnmatchedAndNeverSeeded() {
    // A present-but-malformed GUID parses to null; every maybeSeed*/resolve* guard requires
    // guid != null, so the single bad record is isolated as unmatched — never seeded (which would
    // create a row with no cross-source join key) and never throwing (which would abort the whole
    // all-or-nothing import as a 500).
    when(gameItemRepository.findByClassNameIgnoreCase("wpn_realgun")).thenReturn(List.of());
    when(gameItemRepository.findByNameIgnoreCase("Real Gun")).thenReturn(List.of());

    String json =
        "{\"items\":[{\"guid\":\"not-a-uuid\",\"className\":\"wpn_realgun\","
            + "\"name\":\"Real Gun\"}]}";

    P4kImportResultDto result = service.applyImport(upload(json), true);

    assertEquals(0, result.items().matched());
    assertEquals(0, result.items().created());
    assertEquals(1, result.items().unmatched());
    verify(gameItemRepository, never()).save(any());
  }
}
