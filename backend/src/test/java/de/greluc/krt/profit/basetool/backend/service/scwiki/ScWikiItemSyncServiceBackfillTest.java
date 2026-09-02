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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiItemDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiItemManufacturerDto;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.GameItemKind;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.repository.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.service.SyncReportService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for {@link ScWikiItemSyncService} Mode B — the R5 full Wiki item backfill
 * (SC_WIKI_SYNC_PLAN.md §8.4). Covers mode selection, per-endpoint kind derivation, {@code
 * WIKI_ONLY} creation, the {@code UEX_ONLY → BOTH} flip, the more-specific-wins kind tie-breaker,
 * the §3.4 sanity-cap guard, the junk-name guard, manufacturer resolution and the cross-kind
 * orphan-sweep gating.
 */
@ExtendWith(MockitoExtension.class)
class ScWikiItemSyncServiceBackfillTest {

  private static final String WEAPON_ATTACHMENTS = "/api/weapon-attachments";
  private static final String WEAPONS = "/api/weapons";
  private static final String VEHICLE_WEAPONS = "/api/vehicle-weapons";
  private static final String VEHICLE_ITEMS = "/api/vehicle-items";
  private static final String ARMOR = "/api/armor";
  private static final String CLOTHES = "/api/clothes";
  private static final String FOOD = "/api/food";
  private static final String ITEMS = "/api/items";

  @Mock private ScWikiClient scWikiClient;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private BlueprintRepository blueprintRepository;
  @Mock private ManufacturerRepository manufacturerRepository;
  @Mock private SyncReportService syncReportService;
  @Mock private ObjectProvider<ScWikiItemSyncService> self;

  /**
   * Real registry rather than a mock: {@code @InjectMocks} cannot wire a plain {@code
   * MeterRegistry}, and the sweep-stand-down assertions read counter values back out of it.
   */
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private ScWikiProperties properties;
  private ScWikiItemSyncService service;

  @BeforeEach
  void setUp() {
    properties = new ScWikiProperties();
    properties.setItemSyncEnabled(true);
    properties.setSyncAllItems(true);
    service =
        new ScWikiItemSyncService(
            scWikiClient,
            properties,
            gameItemRepository,
            blueprintRepository,
            manufacturerRepository,
            syncReportService,
            meterRegistry,
            self);
    lenient().when(self.getObject()).thenReturn(service);
    lenient().when(syncReportService.beginRun()).thenReturn(UUID.randomUUID());
    // fetchAllPagesResult returns a FetchResult record (not a List), so Mockito's unstubbed default
    // is null rather than an empty list. Restore the old "unstubbed endpoint -> empty page"
    // behaviour that stubPass relies on: Mode B pages every kind endpoint, and a test stubs only
    // the
    // one(s) it cares about — the rest must resolve to an empty, non-304 page, not NPE (#1182).
    lenient()
        .when(scWikiClient.fetchAllPagesResult(any(), any(), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of()));
  }

  // ---- mode selection -------------------------------------------------------------------------

  @Test
  void syncItems_dispatchesToBackfill_whenSyncAllItemsTrue() {
    stubPass(WEAPONS, itemDto(UUID.randomUUID(), "Behring P4-AR"));

    service.syncItems();

    // Mode B walks list endpoints; it must never fall back to the per-UUID closure fetch.
    verify(scWikiClient, never()).fetchOne(any(), any(), any());
    verify(scWikiClient).fetchAllPagesResult(eq(WEAPONS), any(), any(), any(), any());
  }

  @Test
  void syncItems_dispatchesToClosure_whenSyncAllItemsFalse() {
    properties.setSyncAllItems(false);
    UUID uuid = UUID.randomUUID();
    when(gameItemRepository.findAllExternalUuids()).thenReturn(List.of(uuid));
    when(blueprintRepository.findReferencedItemUuids()).thenReturn(List.of());
    when(scWikiClient.fetchOne(any(), eq(ScWikiItemDto.class), any())).thenReturn(null);

    service.syncItems();

    verify(scWikiClient).fetchOne(eq(ITEMS + "/" + uuid), eq(ScWikiItemDto.class), any());
    verify(scWikiClient, never()).fetchAllPagesResult(any(), any(), any(), any(), any());
  }

  @Test
  void syncItems_isNoOp_whenFeatureFlagOff_evenWithSyncAllItemsTrue() {
    properties.setItemSyncEnabled(false);

    service.syncItems();

    verify(scWikiClient, never()).fetchAllPagesResult(any(), any(), any(), any(), any());
    verify(scWikiClient, never()).fetchOne(any(), any(), any());
  }

  @Test
  void backfill_everyPassNotModified_reportsLiveCount_andSkipsSweep() {
    // Every kind endpoint + the residual /api/items come back 304 (unchanged) — a fully-cached,
    // healthy backfill that upserts nothing. It must report the live Wiki-linked game_item count,
    // NOT 0, so an all-304 run is not read as a zero-item outage (#1182). A genuine empty-200 (no
    // 304) still reports 0 and correctly fires.
    when(scWikiClient.fetchAllPagesResult(any(), any(), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.unchanged());
    when(gameItemRepository.countLiveScwikiItems()).thenReturn(9000L);

    int written = service.syncItems();

    assertEquals(9000, written, "an all-304 backfill must report the live item count, not 0");
    verify(gameItemRepository, never()).save(any());
    // A 304 pass never enumerates the pool, so the cross-kind orphan sweep stays suppressed.
    verify(gameItemRepository, never()).markScwikiDeletedExcept(any(), any());
    // ...and it is counted as `not_modified`, not `incomplete` and not `no_rows`: this run is
    // healthy. Getting that split wrong would make the counter non-zero every night and therefore
    // unalertable — which is why the reason tag exists at all rather than a bare count. It must
    // also not collapse into `no_rows`, which is what a total upstream outage looks like.
    assertEquals(1.0, sweepSkips(MetricNames.SWEEP_SKIP_NOT_MODIFIED));
    assertEquals(0.0, sweepSkips(MetricNames.SWEEP_SKIP_INCOMPLETE));
    assertEquals(0.0, sweepSkips(MetricNames.SWEEP_SKIP_NO_ROWS));
  }

  // ---- per-endpoint kind derivation + WIKI_ONLY creation ---------------------------------------

  @Test
  void backfill_derivesKindFromSourceEndpoint_andCreatesWikiOnlyRows() {
    UUID armorUuid = UUID.randomUUID();
    UUID weaponUuid = UUID.randomUUID();
    stubPass(ARMOR, itemDto(armorUuid, "Pembroke Helmet"));
    stubPass(WEAPONS, itemDto(weaponUuid, "Gallant Rifle"));
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());

    service.syncItems();

    Map<UUID, GameItem> saved = captureSaves();
    assertEquals(GameItemKind.ARMOR, saved.get(armorUuid).getKind());
    assertEquals(GameItemSourceSystem.WIKI_ONLY, saved.get(armorUuid).getSourceSystems());
    assertEquals(GameItemKind.WEAPON, saved.get(weaponUuid).getKind());
    assertEquals(GameItemSourceSystem.WIKI_ONLY, saved.get(weaponUuid).getSourceSystems());
    // The new row records a CREATED_WIKI_ONLY finding.
    verify(syncReportService)
        .logScwikiEvent(
            any(),
            eq(SyncEventType.CREATED_WIKI_ONLY),
            eq("game_item"),
            eq(armorUuid),
            any(),
            any());
  }

  @Test
  void backfill_residualItemsPassCreatesGenericRows() {
    UUID cargoUuid = UUID.randomUUID();
    stubPass(ITEMS, itemDto(cargoUuid, "Titanium Crate"));
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());

    service.syncItems();

    assertEquals(GameItemKind.GENERIC, captureSaves().get(cargoUuid).getKind());
  }

  // ---- existing-row flip + canonical-field protection ------------------------------------------

  @Test
  void backfill_flipsUexOnlyToBoth_andFillsWikiColumns_withoutOverwritingCanonicalFields() {
    UUID uuid = UUID.randomUUID();
    Manufacturer uexMfr = manufacturer("UEX Aegis");
    GameItem existing = new GameItem();
    existing.setExternalUuid(uuid);
    existing.setName("UEX Canonical Name");
    existing.setKind(GameItemKind.GENERIC);
    existing.setManufacturer(uexMfr);
    existing.setSourceSystems(GameItemSourceSystem.UEX_ONLY);

    stubPass(VEHICLE_ITEMS, itemDto(uuid, "Wiki Display Name"));
    when(gameItemRepository.findByExternalUuid(uuid)).thenReturn(Optional.of(existing));

    service.syncItems();

    GameItem result = captureSaves().get(uuid);
    assertEquals(GameItemSourceSystem.BOTH, result.getSourceSystems());
    assertEquals(GameItemKind.VEHICLE_ITEM, result.getKind()); // GENERIC upgraded
    assertEquals("UEX Canonical Name", result.getName()); // UEX name preserved
    assertSame(uexMfr, result.getManufacturer()); // UEX manufacturer sticky
    assertEquals("classif", result.getClassification()); // Wiki column filled
  }

  @Test
  void backfill_neverDowngradesAMoreSpecificExistingKind() {
    UUID uuid = UUID.randomUUID();
    GameItem existing = new GameItem();
    existing.setExternalUuid(uuid);
    existing.setName("Size 3 Cannon");
    existing.setKind(GameItemKind.VEHICLE_WEAPON);
    existing.setSourceSystems(GameItemSourceSystem.UEX_ONLY);

    // The vehicle-items pass would assign VEHICLE_ITEM, which is LESS specific than VEHICLE_WEAPON.
    stubPass(VEHICLE_ITEMS, itemDto(uuid, "Cannon"));
    when(gameItemRepository.findByExternalUuid(uuid)).thenReturn(Optional.of(existing));

    service.syncItems();

    assertEquals(GameItemKind.VEHICLE_WEAPON, captureSaves().get(uuid).getKind());
  }

  // ---- §3.4 sanity-cap guard ------------------------------------------------------------------

  @Test
  void backfill_skipsKindPassThatExceedsSanityCap_butStillRunsOtherPasses() {
    properties.setBackfillKindSanityCap(2);
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    UUID weaponUuid = UUID.randomUUID();
    // Armor comes back pool-sized (3 > cap 2) — the §3.4 full-pool quirk; it must be skipped whole.
    lenient()
        .when(scWikiClient.fetchAllPagesResult(eq(ARMOR), any(), any(), any(), any()))
        .thenReturn(
            ScWikiClient.FetchResult.of(
                List.of(itemDto(a, "A"), itemDto(b, "B"), itemDto(c, "C"))));
    stubPass(WEAPONS, itemDto(weaponUuid, "Rifle"));
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());

    service.syncItems();

    Map<UUID, GameItem> saved = captureSaves();
    assertNull(saved.get(a), "capped armor rows must not be ingested");
    assertEquals(GameItemKind.WEAPON, saved.get(weaponUuid).getKind());
    // A capped pass counts as a failure → orphan sweep is suppressed.
    verify(gameItemRepository, never()).markScwikiDeletedExcept(any(), any());
  }

  // ---- junk-name guard ------------------------------------------------------------------------

  @Test
  void backfill_skipsJunkNamedNewRows_andLogsSkipJunk() {
    UUID uuid = UUID.randomUUID();
    stubPass(WEAPONS, itemDto(uuid, "<= PLACEHOLDER =>"));
    when(gameItemRepository.findByExternalUuid(uuid)).thenReturn(Optional.empty());

    service.syncItems();

    verify(gameItemRepository, never()).save(any());
    verify(syncReportService)
        .logScwikiEvent(
            any(), eq(SyncEventType.SKIP_JUNK), eq("game_item"), eq(uuid), any(), any());
  }

  // ---- manufacturer resolution for new rows ----------------------------------------------------

  @Test
  void backfill_resolvesManufacturerForNewRow_byNameAgainstExistingRowsOnly() {
    UUID uuid = UUID.randomUUID();
    Manufacturer aegis = manufacturer("Aegis Dynamics");
    when(manufacturerRepository.findAll()).thenReturn(List.of(aegis));
    ScWikiItemManufacturerDto mfrRef =
        new ScWikiItemManufacturerDto(UUID.randomUUID(), "aegis dynamics", "AEGS");
    stubPass(WEAPONS, itemDto(uuid, "Gallant", "Cargo", mfrRef));
    when(gameItemRepository.findByExternalUuid(uuid)).thenReturn(Optional.empty());

    service.syncItems();

    assertSame(aegis, captureSaves().get(uuid).getManufacturer());
  }

  @Test
  void backfill_leavesManufacturerNull_whenNoExistingMatch_neverCreatesStub() {
    UUID uuid = UUID.randomUUID();
    ScWikiItemManufacturerDto mfrRef =
        new ScWikiItemManufacturerDto(UUID.randomUUID(), "Unknown Corp", "UNK");
    stubPass(WEAPONS, itemDto(uuid, "Mystery Gun", "Cargo", mfrRef));
    when(gameItemRepository.findByExternalUuid(uuid)).thenReturn(Optional.empty());

    service.syncItems();

    assertNull(captureSaves().get(uuid).getManufacturer());
    verify(manufacturerRepository, never()).save(any());
  }

  // ---- cross-kind orphan sweep gating ----------------------------------------------------------

  @Test
  void backfill_runsOrphanSweep_onlyWhenEveryPassReturnedData() {
    stubPass(WEAPON_ATTACHMENTS, itemDto(UUID.randomUUID(), "Scope"));
    stubPass(WEAPONS, itemDto(UUID.randomUUID(), "Rifle"));
    stubPass(VEHICLE_WEAPONS, itemDto(UUID.randomUUID(), "Cannon"));
    stubPass(VEHICLE_ITEMS, itemDto(UUID.randomUUID(), "Cooler"));
    stubPass(ARMOR, itemDto(UUID.randomUUID(), "Helmet"));
    stubPass(CLOTHES, itemDto(UUID.randomUUID(), "Jacket"));
    stubPass(FOOD, itemDto(UUID.randomUUID(), "Ration"));
    stubPass(ITEMS, itemDto(UUID.randomUUID(), "Crate"));
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());

    service.syncItems();

    // All eight passes (7 kinds + GENERIC residual) returned data → the cross-kind sweep fires.
    verify(gameItemRepository).markScwikiDeletedExcept(any(), any());
    // A run that actually swept must not register the counter at all. Registered-and-zero and
    // absent are different things to the alert rule: absent cannot false-fire.
    assertNull(meterRegistry.find(MetricNames.CATALOGUE_ORPHAN_SWEEP_SKIPPED).counter());
  }

  @Test
  void backfill_skipsOrphanSweep_whenOneKindReturnsEmpty() {
    // All passes but FOOD return data; FOOD is left unstubbed → empty → suppresses the sweep.
    stubPass(WEAPON_ATTACHMENTS, itemDto(UUID.randomUUID(), "Scope"));
    stubPass(WEAPONS, itemDto(UUID.randomUUID(), "Rifle"));
    stubPass(VEHICLE_WEAPONS, itemDto(UUID.randomUUID(), "Cannon"));
    stubPass(VEHICLE_ITEMS, itemDto(UUID.randomUUID(), "Cooler"));
    stubPass(ARMOR, itemDto(UUID.randomUUID(), "Helmet"));
    stubPass(CLOTHES, itemDto(UUID.randomUUID(), "Jacket"));
    stubPass(ITEMS, itemDto(UUID.randomUUID(), "Crate"));
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());

    service.syncItems();

    verify(gameItemRepository, never()).markScwikiDeletedExcept(any(), any());
    // The stand-down is right; being unable to see it was the gap. `business.yml` said in as many
    // words that no metric existed for it, so a sweep that had not run for weeks read exactly like
    // one running cleanly every night.
    assertEquals(1.0, sweepSkips(MetricNames.SWEEP_SKIP_INCOMPLETE));
  }

  /**
   * Reads the orphan-sweep stand-down counter for one reason.
   *
   * @param reason one of the bounded {@code MetricNames.SWEEP_SKIP_*} values.
   * @return the count, or 0 when the counter was never registered for that reason.
   */
  private double sweepSkips(String reason) {
    return meterRegistry
        .find(MetricNames.CATALOGUE_ORPHAN_SWEEP_SKIPPED)
        .tag(MetricNames.TAG_SWEEP, MetricNames.SWEEP_ITEM)
        .tag(MetricNames.TAG_REASON, reason)
        .counters()
        .stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }

  // ---- per-item transaction isolation ----------------------------------------------------------

  @Test
  void backfill_isolatesPerItem_oneDeadlockDoesNotAbortThePass() {
    // Same per-item isolation as the closure mode: a lock failure on one row must not abort the
    // pass — every other row in the page still persists in its own REQUIRES_NEW transaction.
    UUID deadlocked = UUID.randomUUID();
    UUID healthy = UUID.randomUUID();
    lenient()
        .when(scWikiClient.fetchAllPagesResult(eq(WEAPONS), any(), any(), any(), any()))
        .thenReturn(
            ScWikiClient.FetchResult.of(
                List.of(itemDto(deadlocked, "Bad Rifle"), itemDto(healthy, "Good Rifle"))));
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(gameItemRepository.save(any(GameItem.class)))
        .thenAnswer(
            inv -> {
              GameItem candidate = inv.getArgument(0);
              if (deadlocked.equals(candidate.getExternalUuid())) {
                throw new ObjectOptimisticLockingFailureException(GameItem.class, deadlocked);
              }
              return candidate;
            });

    service.syncItems();

    assertEquals(
        GameItemKind.WEAPON,
        captureSaves().get(healthy).getKind(),
        "the healthy row persists despite the sibling row deadlocking");
  }

  // ---- Weg-2 uuid-less UEX reconciliation ------------------------------------------------------

  @Test
  void backfill_reconcilesUuidlessUexRowByName_insteadOfCreatingADuplicate() {
    UUID wikiUuid = UUID.randomUUID();
    UUID uexRowId = UUID.randomUUID();
    GameItem uexRow = uuidlessUex(uexRowId, "Avionics Blade", "avionics-blade");

    when(gameItemRepository.findByExternalUuidIsNullAndSourceSystems(GameItemSourceSystem.UEX_ONLY))
        .thenReturn(List.of(uexRow));
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(gameItemRepository.findById(uexRowId)).thenReturn(Optional.of(uexRow));
    // The Wiki item shares the name (the helper's slug differs, so the name branch resolves it).
    stubPass(VEHICLE_ITEMS, itemDto(wikiUuid, "Avionics Blade"));

    service.syncItems();

    // The existing uuid-less UEX row absorbed the Wiki uuid and flipped to BOTH — no duplicate
    // WIKI_ONLY row, and its Wiki columns are now filled.
    GameItem merged = captureSaves().get(wikiUuid);
    assertSame(uexRow, merged);
    assertEquals(GameItemSourceSystem.BOTH, merged.getSourceSystems());
    assertEquals(wikiUuid, merged.getExternalUuid());
    assertEquals("Avionics Blade", merged.getName());
    assertEquals("classif", merged.getClassification());
    verify(syncReportService)
        .logScwikiEvent(
            any(), eq(SyncEventType.LINKED_VIA_NAME), eq("game_item"), eq(wikiUuid), any(), any());
  }

  @Test
  void backfill_doesNotReconcile_whenNameMatchesMultipleUuidlessUexRows() {
    UUID wikiUuid = UUID.randomUUID();
    GameItem rowA = uuidlessUex(UUID.randomUUID(), "Power Plant", "power-plant-a");
    GameItem rowB = uuidlessUex(UUID.randomUUID(), "Power Plant", "power-plant-b");

    when(gameItemRepository.findByExternalUuidIsNullAndSourceSystems(GameItemSourceSystem.UEX_ONLY))
        .thenReturn(List.of(rowA, rowB));
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    stubPass(VEHICLE_ITEMS, itemDto(wikiUuid, "Power Plant"));

    service.syncItems();

    // The shared name is ambiguous → excluded from the index → no merge; a WIKI_ONLY row is made.
    assertEquals(GameItemSourceSystem.WIKI_ONLY, captureSaves().get(wikiUuid).getSourceSystems());
    verify(gameItemRepository, never()).findById(any());
    verify(syncReportService, never())
        .logScwikiEvent(any(), eq(SyncEventType.LINKED_VIA_NAME), any(), any(), any(), any());
  }

  @Test
  void backfill_skipsReconciliation_whenFlagOff_evenWithAMatchingUexRow() {
    properties.setReconcileUuidlessByName(false);
    UUID wikiUuid = UUID.randomUUID();
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    stubPass(VEHICLE_ITEMS, itemDto(wikiUuid, "Avionics Blade"));

    service.syncItems();

    // Flag off → the uuid-less index is never even queried and a WIKI_ONLY row is created.
    assertEquals(GameItemSourceSystem.WIKI_ONLY, captureSaves().get(wikiUuid).getSourceSystems());
    verify(gameItemRepository, never()).findByExternalUuidIsNullAndSourceSystems(any());
    verify(gameItemRepository, never()).findById(any());
  }

  @Test
  void backfill_consumesEachUexRowOnce_soASecondNameTwinBecomesWikiOnly() {
    UUID firstWiki = UUID.randomUUID();
    UUID secondWiki = UUID.randomUUID();
    UUID uexRowId = UUID.randomUUID();
    GameItem uexRow = uuidlessUex(uexRowId, "Cooler", "cooler-x");

    when(gameItemRepository.findByExternalUuidIsNullAndSourceSystems(GameItemSourceSystem.UEX_ONLY))
        .thenReturn(List.of(uexRow));
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(gameItemRepository.findById(uexRowId)).thenReturn(Optional.of(uexRow));
    lenient()
        .when(scWikiClient.fetchAllPagesResult(eq(VEHICLE_ITEMS), any(), any(), any(), any()))
        .thenReturn(
            ScWikiClient.FetchResult.of(
                List.of(itemDto(firstWiki, "Cooler"), itemDto(secondWiki, "Cooler"))));

    service.syncItems();

    Map<UUID, GameItem> saved = captureSaves();
    // The first twin merged into the UEX row; the second could not re-consume it → WIKI_ONLY.
    assertEquals(GameItemSourceSystem.BOTH, saved.get(firstWiki).getSourceSystems());
    assertEquals(GameItemSourceSystem.WIKI_ONLY, saved.get(secondWiki).getSourceSystems());
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /**
   * Stubs the 5-arg {@code fetchAllPages} for a single endpoint to return exactly the given row.
   *
   * @param endpoint the endpoint to stub
   * @param row the single row the pass returns
   */
  private void stubPass(String endpoint, ScWikiItemDto row) {
    // Lenient: Mode B always pages every endpoint, so the per-endpoint stubs that a given test does
    // not exercise (other kinds return the default empty list) must not trip strict stubbing.
    lenient()
        .when(scWikiClient.fetchAllPagesResult(eq(endpoint), any(), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(row)));
  }

  /**
   * Captures every {@link GameItem} passed to {@code gameItemRepository.save}, keyed by external
   * UUID for direct per-item assertions.
   *
   * @return the saved game items keyed by {@code external_uuid}
   */
  private Map<UUID, GameItem> captureSaves() {
    ArgumentCaptor<GameItem> captor = ArgumentCaptor.forClass(GameItem.class);
    verify(gameItemRepository, atLeastOnce()).save(captor.capture());
    return captor.getAllValues().stream()
        .collect(Collectors.toMap(GameItem::getExternalUuid, gi -> gi, (first, second) -> second));
  }

  /**
   * Builds a minimal valid Wiki item payload with a clean name and no manufacturer.
   *
   * @param uuid the asset UUID
   * @param name the display name
   * @return the payload
   */
  private static ScWikiItemDto itemDto(UUID uuid, String name) {
    return itemDto(uuid, name, "Cargo", null);
  }

  /**
   * Builds a Wiki item payload with an explicit Wiki {@code type} and manufacturer reference.
   *
   * @param uuid the asset UUID
   * @param name the display name
   * @param type the Wiki type token
   * @param manufacturer the nested manufacturer reference, or {@code null}
   * @return the payload
   */
  private static ScWikiItemDto itemDto(
      UUID uuid, String name, String type, ScWikiItemManufacturerDto manufacturer) {
    return new ScWikiItemDto(
        uuid,
        "slug-" + name,
        name,
        "class_name",
        "classif",
        "classifLabel",
        type,
        "typeLabel",
        "subType",
        "subTypeLabel",
        "1",
        "A",
        "common",
        1.0,
        null,
        manufacturer,
        Map.of("en_EN", "desc"),
        Boolean.TRUE,
        Boolean.FALSE,
        "4.8.0-LIVE");
  }

  /**
   * Builds a detached manufacturer with the given name (no-op identity for {@code assertSame}).
   *
   * @param name the manufacturer name
   * @return the manufacturer
   */
  private static Manufacturer manufacturer(String name) {
    Manufacturer m = new Manufacturer();
    m.setName(name);
    return m;
  }

  /**
   * Builds a detached uuid-less {@code UEX_ONLY} game item — the Weg-2 reconciliation target — with
   * the given id, canonical name and {@code uex_slug}.
   *
   * @param id the row id (matched back via {@code findById})
   * @param name the UEX-canonical name used for the name index
   * @param uexSlug the {@code uex_slug} used for the slug index
   * @return the detached uuid-less UEX row
   */
  private static GameItem uuidlessUex(UUID id, String name, String uexSlug) {
    GameItem g = new GameItem();
    g.setId(id);
    g.setName(name);
    g.setUexSlug(uexSlug);
    g.setKind(GameItemKind.VEHICLE_ITEM);
    g.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
    return g;
  }
}
