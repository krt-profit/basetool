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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReconciliationBusyException;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.GameItemStatWriter;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ItemClassResolver;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ItemReconciliationService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiItemFieldWriter;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiItemStatFollowUp;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiItemSyncService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncEventType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncReportService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemManufacturerDto;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for the full Wiki item backfill (Mode B) of {@link ScWikiItemSyncService}: mode
 * selection, per-endpoint kind derivation, {@code WIKI_ONLY} creation, the {@code UEX_ONLY → BOTH}
 * flip, the kind tie-breaker, the sanity cap, the junk-name guard, manufacturer resolution and
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
  @Mock private ItemReconciliationService itemReconciliationService;
  @Mock private GameItemStatRepository gameItemStatRepository;
  @Mock private ObjectProvider<ScWikiItemSyncService> self;

  /**
   * Real registry rather than a mock: {@code @InjectMocks} cannot wire a plain {@code
   * MeterRegistry}, and the sweep-stand-down assertions read counter values back out of it.
   */
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private ScWikiProperties properties;

  /** The configured keys, relative to the record's prefix; {@link #rebuild()} binds them. */
  private final Map<String, Object> config = new HashMap<>();

  private ScWikiItemSyncService service;

  @BeforeEach
  void setUp() {
    config.putAll(Map.of("item-sync-enabled", true, "sync-all-items", true));
    rebuild();
    lenient().when(syncReportService.beginRun()).thenReturn(UUID.randomUUID());
    lenient()
        .when(scWikiClient.fetchAllPagesResult(any(), any(), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of()));
  }

  /**
   * Binds the properties record from {@link #config} and builds the object under test over it. The
   * record is immutable (BE-MOD-04), so a test that changes a key rebuilds.
   */
  private void rebuild() {
    properties = BoundProperties.bind(ScWikiProperties.class, config);
    service =
        new ScWikiItemSyncService(
            scWikiClient,
            properties,
            gameItemRepository,
            blueprintRepository,
            manufacturerRepository,
            syncReportService,
            gameItemStatRepository,
            new GameItemStatWriter(gameItemStatRepository),
            new ScWikiItemStatFollowUp(
                scWikiClient, properties, gameItemStatRepository, syncReportService),
            itemReconciliationService,
            new ScWikiItemFieldWriter(new ItemClassResolver(meterRegistry), syncReportService),
            meterRegistry,
            self);
    lenient().when(self.getObject()).thenReturn(service);
  }

  @Test
  void syncItems_dispatchesToBackfill_whenSyncAllItemsTrue() {
    stubPass(WEAPONS, itemDto(UUID.randomUUID(), "Behring P4-AR"));

    service.syncItems();

    verify(scWikiClient, never()).fetchOne(any(), any(), any(), any());
    verify(scWikiClient).fetchAllPagesResult(eq(WEAPONS), any(), any(), any(), any());
  }

  @Test
  void syncItems_backfill_fetchesBlueprintOutputsWithoutStatsInFullAndSetsTheGauge() {
    stubPass(WEAPONS, itemDto(UUID.randomUUID(), "Behring P4-AR"));
    UUID output = UUID.randomUUID();
    when(gameItemStatRepository.findBlueprintOutputUuidsWithoutStats()).thenReturn(List.of(output));
    when(gameItemStatRepository.countItemsWithStats()).thenReturn(42L);
    when(scWikiClient.fetchOne(eq(ITEMS), eq(output), eq(ScWikiItemDto.class), any()))
        .thenReturn(null);

    service.syncItems();

    verify(scWikiClient).fetchOne(eq(ITEMS), eq(output), eq(ScWikiItemDto.class), any());
    assertEquals(
        42.0, meterRegistry.get(CatalogueMetricNames.CATALOGUE_ITEMS_WITH_STATS).gauge().value());
  }

  @Test
  void statFollowUp_rejectsMismatchedMockedDetailWithoutMetadataOrStatsWrites() {
    UUID requested = UUID.randomUUID();
    when(gameItemStatRepository.findBlueprintOutputUuidsWithoutStats())
        .thenReturn(List.of(requested));
    when(scWikiClient.fetchOne(eq(ITEMS), eq(requested), eq(ScWikiItemDto.class), any()))
        .thenReturn(itemDto(UUID.randomUUID(), "Wrong Asset"));

    assertEquals(0, service.syncItems());

    verify(syncReportService).recordFailed(1);
    verify(syncReportService).recordIncomplete();
    verify(syncReportService)
        .logScwikiEvent(
            any(),
            eq(SyncEventType.SOURCE_IDENTITY_CONFLICT),
            eq("game_item"),
            eq(requested),
            isNull(),
            any());
    verify(gameItemRepository, never()).findByExternalUuid(any());
    verify(gameItemRepository, never()).save(any());
    verify(gameItemStatRepository, never()).findByGameItemId(any());
    verify(gameItemStatRepository, never()).save(any());
    verify(syncReportService, never()).recordApplied(anyLong());
  }

  @Test
  void syncItems_dispatchesToClosure_whenSyncAllItemsFalse() {
    config.put("sync-all-items", false);
    rebuild();
    UUID uuid = UUID.randomUUID();
    when(gameItemRepository.findAllExternalUuids()).thenReturn(List.of(uuid));
    when(blueprintRepository.findReferencedItemUuids()).thenReturn(List.of());
    when(scWikiClient.fetchOneResult(any(), any(), eq(ScWikiItemDto.class), any()))
        .thenReturn(new ScWikiClient.DetailResult<>(ScWikiClient.DetailOutcome.MISSING, null));

    service.syncItems();

    verify(scWikiClient).fetchOneResult(eq(ITEMS), eq(uuid), eq(ScWikiItemDto.class), any());
    verify(scWikiClient, never()).fetchAllPagesResult(any(), any(), any(), any(), any());
  }

  @Test
  void syncItems_isNoOp_whenFeatureFlagOff_evenWithSyncAllItemsTrue() {
    config.put("item-sync-enabled", false);
    rebuild();

    service.syncItems();

    verify(scWikiClient, never()).fetchAllPagesResult(any(), any(), any(), any(), any());
    verify(scWikiClient, never()).fetchOne(any(), any(), any(), any());
  }

  @Test
  void repeatedBackfillPayloadsRefreshTheSameSourceRowAgain() {
    UUID uuid = UUID.randomUUID();
    ScWikiItemDto dto = itemDto(uuid, "Replay item");
    stubPass(ITEMS, dto);
    GameItem existing = new GameItem();
    existing.setId(UUID.randomUUID());
    existing.setExternalUuid(uuid);
    existing.setName("Replay item");
    existing.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    when(gameItemRepository.findByExternalUuid(uuid)).thenReturn(Optional.of(existing));

    assertEquals(1, service.syncItems());
    existing.setScwikiName("stale");
    assertEquals(1, service.syncItems());

    assertEquals(dto.name(), existing.getScwikiName());
    verify(gameItemRepository, times(2)).save(existing);
    verify(scWikiClient, times(2)).fetchAllPagesResult(eq(ITEMS), any(), any(), any(), any());
  }

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

  @Test
  void backfill_flipsUexOnlyToBoth_fillsWikiColumns_andTakesTheWikiNameButKeepsTheManufacturer() {
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
    assertEquals(GameItemKind.VEHICLE_ITEM, result.getKind());
    assertEquals("Wiki Display Name", result.getName());
    assertSame(uexMfr, result.getManufacturer());
    assertEquals("classif", result.getClassification());
  }

  @Test
  void backfill_neverRenamesARowTheP4kKnows() {
    UUID uuid = UUID.randomUUID();
    GameItem existing = new GameItem();
    existing.setExternalUuid(uuid);
    existing.setName("P4K Archive Name");
    existing.setKind(GameItemKind.GENERIC);
    existing.setSourceSystems(GameItemSourceSystem.BOTH);
    existing.setP4kName("P4K Archive Name");

    stubPass(VEHICLE_ITEMS, itemDto(uuid, "Wiki Display Name"));
    when(gameItemRepository.findByExternalUuid(uuid)).thenReturn(Optional.of(existing));

    service.syncItems();

    GameItem result = captureSaves().get(uuid);
    assertEquals("P4K Archive Name", result.getName());
    assertEquals("Wiki Display Name", result.getScwikiName());
  }

  @Test
  void backfill_neverDowngradesAMoreSpecificExistingKind() {
    UUID uuid = UUID.randomUUID();
    GameItem existing = new GameItem();
    existing.setExternalUuid(uuid);
    existing.setName("Size 3 Cannon");
    existing.setKind(GameItemKind.VEHICLE_WEAPON);
    existing.setSourceSystems(GameItemSourceSystem.UEX_ONLY);

    stubPass(VEHICLE_ITEMS, itemDto(uuid, "Cannon"));
    when(gameItemRepository.findByExternalUuid(uuid)).thenReturn(Optional.of(existing));

    service.syncItems();

    assertEquals(GameItemKind.VEHICLE_WEAPON, captureSaves().get(uuid).getKind());
  }

  @Test
  void backfill_skipsKindPassThatExceedsSanityCap_butStillRunsOtherPasses() {
    config.put("backfill-kind-sanity-cap", 2);
    rebuild();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    UUID weaponUuid = UUID.randomUUID();
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
    verify(gameItemRepository, never()).markScwikiDeletedExcept(any(), any());
  }

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

    verify(gameItemRepository).markScwikiDeletedExcept(any(), any());
    assertNull(meterRegistry.find(CatalogueMetricNames.CATALOGUE_ORPHAN_SWEEP_SKIPPED).counter());
  }

  @Test
  void backfill_skipsOrphanSweep_whenOneKindReturnsEmpty() {
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
    assertEquals(1.0, sweepSkips(CatalogueMetricNames.SWEEP_SKIP_INCOMPLETE));
  }

  @Test
  void
      backfill_runsOrphanSweep_whenAKindPassIsIncomplete_butTheResidualCensusCoversEveryRowItSaw() {
    ScWikiItemDto scope = itemDto(UUID.randomUUID(), "Scope");
    ScWikiItemDto rifle = itemDto(UUID.randomUUID(), "Rifle");
    ScWikiItemDto cannon = itemDto(UUID.randomUUID(), "Cannon");
    ScWikiItemDto cooler = itemDto(UUID.randomUUID(), "Cooler");
    ScWikiItemDto helmet = itemDto(UUID.randomUUID(), "Helmet");
    ScWikiItemDto jacket = itemDto(UUID.randomUUID(), "Jacket");
    ScWikiItemDto ration = itemDto(UUID.randomUUID(), "Ration");
    ScWikiItemDto crate = itemDto(UUID.randomUUID(), "Crate");
    stubPass(WEAPON_ATTACHMENTS, scope);
    stubPass(WEAPONS, rifle);
    stubPass(VEHICLE_WEAPONS, cannon);
    stubPartialPass(VEHICLE_ITEMS, cooler);
    stubPass(ARMOR, helmet);
    stubPass(CLOTHES, jacket);
    stubPass(FOOD, ration);
    stubPassRows(ITEMS, scope, rifle, cannon, cooler, helmet, jacket, ration, crate);
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());

    service.syncItems();

    verify(gameItemRepository).markScwikiDeletedExcept(any(), any());
    assertNull(meterRegistry.find(CatalogueMetricNames.CATALOGUE_ORPHAN_SWEEP_SKIPPED).counter());
  }

  @Test
  void backfill_skipsOrphanSweep_whenAnIncompleteKindPassSawARowOutsideTheResidualPool() {
    ScWikiItemDto scope = itemDto(UUID.randomUUID(), "Scope");
    ScWikiItemDto rifle = itemDto(UUID.randomUUID(), "Rifle");
    ScWikiItemDto cannon = itemDto(UUID.randomUUID(), "Cannon");
    ScWikiItemDto cooler = itemDto(UUID.randomUUID(), "Cooler");
    ScWikiItemDto helmet = itemDto(UUID.randomUUID(), "Helmet");
    ScWikiItemDto jacket = itemDto(UUID.randomUUID(), "Jacket");
    ScWikiItemDto ration = itemDto(UUID.randomUUID(), "Ration");
    ScWikiItemDto crate = itemDto(UUID.randomUUID(), "Crate");
    stubPass(WEAPON_ATTACHMENTS, scope);
    stubPass(WEAPONS, rifle);
    stubPass(VEHICLE_WEAPONS, cannon);
    stubPartialPass(VEHICLE_ITEMS, cooler);
    stubPass(ARMOR, helmet);
    stubPass(CLOTHES, jacket);
    stubPass(FOOD, ration);
    stubPassRows(ITEMS, scope, rifle, cannon, helmet, jacket, ration, crate);
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());

    service.syncItems();

    verify(gameItemRepository, never()).markScwikiDeletedExcept(any(), any());
    assertEquals(1.0, sweepSkips(CatalogueMetricNames.SWEEP_SKIP_INCOMPLETE));
  }

  @Test
  void backfill_skipsOrphanSweep_whenTheResidualPassItselfCannotVouchForItsCensus() {
    stubPass(WEAPON_ATTACHMENTS, itemDto(UUID.randomUUID(), "Scope"));
    stubPass(WEAPONS, itemDto(UUID.randomUUID(), "Rifle"));
    stubPass(VEHICLE_WEAPONS, itemDto(UUID.randomUUID(), "Cannon"));
    stubPass(VEHICLE_ITEMS, itemDto(UUID.randomUUID(), "Cooler"));
    stubPass(ARMOR, itemDto(UUID.randomUUID(), "Helmet"));
    stubPass(CLOTHES, itemDto(UUID.randomUUID(), "Jacket"));
    stubPass(FOOD, itemDto(UUID.randomUUID(), "Ration"));
    stubPartialPass(ITEMS, itemDto(UUID.randomUUID(), "Crate"));
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());

    service.syncItems();

    verify(gameItemRepository, never()).markScwikiDeletedExcept(any(), any());
    assertEquals(1.0, sweepSkips(CatalogueMetricNames.SWEEP_SKIP_INCOMPLETE));
  }

  /**
   * Reads the orphan-sweep stand-down counter for one reason.
   *
   * @param reason one of the bounded {@code MetricNames.SWEEP_SKIP_*} values.
   * @return the count, or 0 when the counter was never registered for that reason.
   */
  private double sweepSkips(String reason) {
    return meterRegistry
        .find(CatalogueMetricNames.CATALOGUE_ORPHAN_SWEEP_SKIPPED)
        .tag(CatalogueMetricNames.TAG_SWEEP, CatalogueMetricNames.SWEEP_ITEM)
        .tag(MetricNames.TAG_REASON, reason)
        .counters()
        .stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }

  @Test
  void backfill_isolatesPerItem_oneDeadlockDoesNotAbortThePass() {
    UUID deadlocked = UUID.randomUUID();
    UUID healthy = UUID.randomUUID();
    lenient()
        .when(scWikiClient.fetchAllPagesResult(eq(WEAPONS), any(), any(), any(), any()))
        .thenReturn(
            ScWikiClient.FetchResult.of(
                List.of(itemDto(deadlocked, "Bad Rifle"), itemDto(healthy, "Good Rifle"))));
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(gameItemRepository.save(any(GameItem.class)))
        .thenAnswer(inv -> {
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

  @Test
  void aBusyReferenceOwnerRetriesTheIsolatedWriterAndCountsOnlyTheCommit() {
    ScWikiItemDto dto = itemDto(UUID.randomUUID(), "Contended Item");
    stubPass(ITEMS, dto);
    ScWikiItemSyncService writer = mock(ScWikiItemSyncService.class);
    when(self.getObject()).thenReturn(writer);
    when(writer.upsertBackfillItemWithinTransaction(any(), any(), any(), any(), any(), any()))
        .thenThrow(new GameItemReconciliationBusyException())
        .thenReturn(ScWikiItemSyncService.BackfillOutcome.RECONCILED);

    assertEquals(1, service.syncItems());

    verify(writer, times(2))
        .upsertBackfillItemWithinTransaction(any(), any(), any(), any(), any(), any());
    verify(syncReportService).recordApplied(1);
    verify(syncReportService, never()).recordFailed(anyLong());
  }

  @Test
  void referenceOwnerContentionIsBoundedAndDefersWithoutAnOrphanSweep() {
    stubPass(ITEMS, itemDto(UUID.randomUUID(), "Contended Item"));
    ScWikiItemSyncService writer = mock(ScWikiItemSyncService.class);
    when(self.getObject()).thenReturn(writer);
    when(writer.upsertBackfillItemWithinTransaction(any(), any(), any(), any(), any(), any()))
        .thenThrow(new GameItemReconciliationBusyException());

    assertEquals(0, service.syncItems());

    verify(writer, times(3))
        .upsertBackfillItemWithinTransaction(any(), any(), any(), any(), any(), any());
    verify(syncReportService).recordFailed(1);
    verify(syncReportService, never()).recordApplied(anyLong());
    verify(writer, never()).markBackfillOrphansWithinTransaction(any(), any());
  }

  @Test
  void backfill_reconcilesUuidlessUexRowByName_insteadOfCreatingADuplicate() {
    UUID wikiUuid = UUID.randomUUID();
    UUID uexRowId = UUID.randomUUID();
    GameItem uexRow = uuidlessUex(uexRowId, "Avionics Blade", "avionics-blade");

    when(gameItemRepository.findByExternalUuidIsNullAndSourceSystems(GameItemSourceSystem.UEX_ONLY))
        .thenReturn(List.of(uexRow));
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(gameItemRepository.findById(uexRowId)).thenReturn(Optional.of(uexRow));
    stubPass(VEHICLE_ITEMS, itemDto(wikiUuid, "Avionics Blade"));
    stubPass(ITEMS, itemDto(wikiUuid, "Avionics Blade"));

    service.syncItems();

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
    stubPass(ITEMS, itemDto(wikiUuid, "Power Plant"));

    service.syncItems();

    assertEquals(GameItemSourceSystem.WIKI_ONLY, captureSaves().get(wikiUuid).getSourceSystems());
    verify(gameItemRepository, never()).findById(any());
    verify(syncReportService)
        .logScwikiEvent(
            any(),
            eq(SyncEventType.MULTI_MATCH_AMBIGUOUS),
            eq("game_item"),
            eq(wikiUuid),
            eq("Power Plant"),
            eq("Exact-name reconciliation declined;reason=UEX_NAME"));
    verify(syncReportService, never())
        .logScwikiEvent(any(), eq(SyncEventType.LINKED_VIA_NAME), any(), any(), any(), any());
  }

  @Test
  void backfill_skipsReconciliation_whenFlagOff_evenWithAMatchingUexRow() {
    config.put("reconcile-uuidless-by-name", false);
    rebuild();
    UUID wikiUuid = UUID.randomUUID();
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    stubPass(VEHICLE_ITEMS, itemDto(wikiUuid, "Avionics Blade"));

    service.syncItems();

    assertEquals(GameItemSourceSystem.WIKI_ONLY, captureSaves().get(wikiUuid).getSourceSystems());
    verify(gameItemRepository, never()).findByExternalUuidIsNullAndSourceSystems(any());
    verify(gameItemRepository, never()).findById(any());
    verify(syncReportService, never())
        .logScwikiEvent(any(), eq(SyncEventType.MULTI_MATCH_AMBIGUOUS), any(), any(), any(), any());
  }

  @Test
  void backfill_rejectsBothWikiNameTwinsBeforeEitherCanConsumeTheUexRow() {
    UUID firstWiki = UUID.randomUUID();
    UUID secondWiki = UUID.randomUUID();
    UUID uexRowId = UUID.randomUUID();
    GameItem uexRow = uuidlessUex(uexRowId, "Cooler", "cooler-x");

    when(gameItemRepository.findByExternalUuidIsNullAndSourceSystems(GameItemSourceSystem.UEX_ONLY))
        .thenReturn(List.of(uexRow));
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    lenient()
        .when(scWikiClient.fetchAllPagesResult(eq(VEHICLE_ITEMS), any(), any(), any(), any()))
        .thenReturn(
            ScWikiClient.FetchResult.of(
                List.of(itemDto(firstWiki, "Cooler"), itemDto(secondWiki, "Cooler"))));
    stubPassRows(ITEMS, itemDto(firstWiki, "Cooler"), itemDto(secondWiki, "Cooler"));

    service.syncItems();

    Map<UUID, GameItem> saved = captureSaves();
    assertEquals(GameItemSourceSystem.WIKI_ONLY, saved.get(firstWiki).getSourceSystems());
    assertEquals(GameItemSourceSystem.WIKI_ONLY, saved.get(secondWiki).getSourceSystems());
    assertNull(uexRow.getExternalUuid());
    verify(gameItemRepository, never()).findById(any());
    verify(syncReportService)
        .logScwikiEvent(
            any(),
            eq(SyncEventType.MULTI_MATCH_AMBIGUOUS),
            eq("game_item"),
            eq(firstWiki),
            eq("Cooler"),
            eq("Exact-name reconciliation declined;reason=WIKI_NAME"));
    verify(syncReportService)
        .logScwikiEvent(
            any(),
            eq(SyncEventType.MULTI_MATCH_AMBIGUOUS),
            eq("game_item"),
            eq(secondWiki),
            eq("Cooler"),
            eq("Exact-name reconciliation declined;reason=WIKI_NAME"));
  }

  @Test
  void backfill_nameReconciliationRejectsAnIncompatibleItemKind() {
    UUID wikiUuid = UUID.randomUUID();
    GameItem uexRow = uuidlessUex(UUID.randomUUID(), "Same Name", "different-slug");
    uexRow.setKind(GameItemKind.ARMOR);
    when(gameItemRepository.findByExternalUuidIsNullAndSourceSystems(GameItemSourceSystem.UEX_ONLY))
        .thenReturn(List.of(uexRow));
    when(gameItemRepository.findById(uexRow.getId())).thenReturn(Optional.of(uexRow));
    stubPass(VEHICLE_ITEMS, itemDto(wikiUuid, "Same Name"));
    stubPass(ITEMS, itemDto(wikiUuid, "Same Name"));

    service.syncItems();

    assertNull(uexRow.getExternalUuid());
    assertEquals(GameItemSourceSystem.WIKI_ONLY, captureSaves().get(wikiUuid).getSourceSystems());
  }

  @Test
  void existingWikiRowsPassTheirCurrentEndpointFactsToTheLockedMerger() {
    UUID wikiUuid = UUID.randomUUID();
    GameItem wiki = new GameItem();
    wiki.setId(UUID.randomUUID());
    wiki.setExternalUuid(wikiUuid);
    wiki.setName("Same Name");
    wiki.setKind(GameItemKind.GENERIC);
    wiki.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    GameItem armor = uuidlessUex(UUID.randomUUID(), "Same Name", "unrelated-slug");
    armor.setKind(GameItemKind.ARMOR);
    when(gameItemRepository.findByExternalUuidIsNullAndSourceSystems(GameItemSourceSystem.UEX_ONLY))
        .thenReturn(List.of(armor));
    when(gameItemRepository.findByExternalUuid(wikiUuid)).thenReturn(Optional.of(wiki));
    stubPass(WEAPONS, itemDto(wikiUuid, "Same Name"));
    stubPass(ITEMS, itemDto(wikiUuid, "Same Name"));

    service.syncItems();

    ArgumentCaptor<ItemReconciliationService.WikiIdentity> incoming = ArgumentCaptor.captor();
    verify(itemReconciliationService)
        .mergeExisting(eq(wiki), eq(armor.getId()), any(), incoming.capture());
    assertEquals(GameItemKind.WEAPON, incoming.getValue().kind());
    assertEquals("Same Name", incoming.getValue().name());
    assertEquals(GameItemKind.WEAPON, wiki.getKind());
    assertNull(armor.getExternalUuid());
  }

  @Test
  void backfill_doesNotReconcileWithoutACompleteWikiIdentityCensus() {
    UUID wikiUuid = UUID.randomUUID();
    GameItem uexRow = uuidlessUex(UUID.randomUUID(), "Same Name", "different-slug");
    when(gameItemRepository.findByExternalUuidIsNullAndSourceSystems(GameItemSourceSystem.UEX_ONLY))
        .thenReturn(List.of(uexRow));
    stubPass(VEHICLE_ITEMS, itemDto(wikiUuid, "Same Name"));
    stubPartialPass(ITEMS, itemDto(wikiUuid, "Same Name"));

    service.syncItems();

    assertNull(uexRow.getExternalUuid());
    verify(gameItemRepository, never()).findById(any());
    verify(syncReportService, never())
        .logScwikiEvent(any(), eq(SyncEventType.MULTI_MATCH_AMBIGUOUS), any(), any(), any(), any());
  }

  @Test
  void aLiveItemOutsidePlayerScopeIsProtectedByTheFullExistenceCensus() {
    UUID player = UUID.randomUUID();
    UUID internal = UUID.randomUUID();
    stubPass(ITEMS, itemDto(player, "Player Item"));
    when(gameItemRepository.findKnownWikiExternalUuids()).thenReturn(List.of(player, internal));
    when(scWikiClient.fetchExistenceCensus(ITEMS, "items", properties.pageSize()))
        .thenReturn(new ScWikiClient.ExistenceCensus(Set.of(player, internal), true));

    service.syncItems();

    verify(gameItemRepository)
        .markScwikiDeletedExcept(
            argThat(ids -> ids.contains(player) && ids.contains(internal)), any());
    verify(gameItemRepository, times(1)).save(any(GameItem.class));
  }

  @Test
  void aLiveItemAbsentFromTheCompleteFullScopeMayBeTombstoned() {
    UUID player = UUID.randomUUID();
    UUID removed = UUID.randomUUID();
    stubPass(ITEMS, itemDto(player, "Player Item"));
    when(gameItemRepository.findKnownWikiExternalUuids()).thenReturn(List.of(player, removed));
    when(scWikiClient.fetchExistenceCensus(ITEMS, "items", properties.pageSize()))
        .thenReturn(new ScWikiClient.ExistenceCensus(Set.of(player), true));

    service.syncItems();

    verify(gameItemRepository)
        .markScwikiDeletedExcept(
            argThat(ids -> ids.contains(player) && !ids.contains(removed)), any());
  }

  @Test
  void anIncompleteFullScopeCannotProveThatAnOmittedLiveItemWasDeleted() {
    UUID player = UUID.randomUUID();
    UUID omitted = UUID.randomUUID();
    stubPass(ITEMS, itemDto(player, "Player Item"));
    when(gameItemRepository.findKnownWikiExternalUuids()).thenReturn(List.of(player, omitted));
    when(scWikiClient.fetchExistenceCensus(ITEMS, "items", properties.pageSize()))
        .thenReturn(new ScWikiClient.ExistenceCensus(Set.of(player), false));

    service.syncItems();

    verify(gameItemRepository, never()).markScwikiDeletedExcept(any(), any());
  }

  @Test
  void aFullScopeThatDoesNotContainTheRelevantCensusCannotVouchForDeletions() {
    UUID player = UUID.randomUUID();
    UUID omitted = UUID.randomUUID();
    stubPass(ITEMS, itemDto(player, "Player Item"));
    when(gameItemRepository.findKnownWikiExternalUuids()).thenReturn(List.of(player, omitted));
    when(scWikiClient.fetchExistenceCensus(ITEMS, "items", properties.pageSize()))
        .thenReturn(new ScWikiClient.ExistenceCensus(Set.of(omitted), true));

    service.syncItems();

    verify(gameItemRepository, never()).markScwikiDeletedExcept(any(), any());
  }

  @Test
  void noFullScopeRequestIsNeededWhenEveryLiveIdentityWasSeenInPlayerScope() {
    UUID player = UUID.randomUUID();
    stubPass(ITEMS, itemDto(player, "Player Item"));
    when(gameItemRepository.findKnownWikiExternalUuids()).thenReturn(List.of(player));

    service.syncItems();

    verify(scWikiClient, never()).fetchExistenceCensus(any(), any(), anyInt());
    verify(gameItemRepository).markScwikiDeletedExcept(any(), any());
  }

  @Test
  void aKnownTombstoneIsRestoredOnlyAfterPositiveFullScopeEvidence() {
    UUID player = UUID.randomUUID();
    UUID tombstone = UUID.randomUUID();
    stubPass(ITEMS, itemDto(player, "Player Item"));
    when(gameItemRepository.findKnownWikiExternalUuids()).thenReturn(List.of(player, tombstone));
    when(scWikiClient.fetchExistenceCensus(ITEMS, "items", properties.pageSize()))
        .thenReturn(new ScWikiClient.ExistenceCensus(Set.of(player, tombstone), true));
    when(gameItemRepository.restoreKnownWikiItems(any(), any())).thenReturn(1);

    assertEquals(2, service.syncItems());

    verify(gameItemRepository)
        .restoreKnownWikiItems(argThat(ids -> ids.equals(List.of(tombstone))), any());
    verify(syncReportService)
        .logScwikiEvent(
            any(),
            eq(SyncEventType.SOURCE_RECORDS_RESTORED),
            eq("game_item"),
            any(),
            any(),
            eq("restored=1;scope=all_assets"));
  }

  @Test
  void anAbsentKnownTombstoneIsNeverRestored() {
    UUID player = UUID.randomUUID();
    UUID tombstone = UUID.randomUUID();
    stubPass(ITEMS, itemDto(player, "Player Item"));
    when(gameItemRepository.findKnownWikiExternalUuids()).thenReturn(List.of(player, tombstone));
    when(scWikiClient.fetchExistenceCensus(ITEMS, "items", properties.pageSize()))
        .thenReturn(new ScWikiClient.ExistenceCensus(Set.of(player), true));

    service.syncItems();

    verify(gameItemRepository, never()).restoreKnownWikiItems(any(), any());
  }

  @Test
  void restorationFailureCannotBeFollowedByAnOrphanSweep() {
    UUID player = UUID.randomUUID();
    UUID tombstone = UUID.randomUUID();
    stubPass(ITEMS, itemDto(player, "Player Item"));
    when(gameItemRepository.findKnownWikiExternalUuids()).thenReturn(List.of(player, tombstone));
    when(scWikiClient.fetchExistenceCensus(ITEMS, "items", properties.pageSize()))
        .thenReturn(new ScWikiClient.ExistenceCensus(Set.of(player, tombstone), true));
    when(gameItemRepository.restoreKnownWikiItems(any(), any()))
        .thenThrow(new DataIntegrityViolationException("rejected correction"));

    assertThrows(DataIntegrityViolationException.class, service::syncItems);

    verify(gameItemRepository, never()).markScwikiDeletedExcept(any(), any());
    verify(syncReportService).recordFailed(1);
  }

  @Test
  void positivePresenceUpdatesUseBoundedBatchesWithoutLosingAnyIdentity() {
    Set<UUID> ids =
        java.util.stream.IntStream.rangeClosed(1, 1200)
            .mapToObj(value -> new UUID(0, value))
            .collect(Collectors.toSet());
    when(gameItemRepository.restoreKnownWikiItems(any(), any()))
        .thenAnswer(inv -> inv.<java.util.Collection<UUID>>getArgument(0).size());

    assertEquals(
        1200,
        service.restoreKnownItemsWithinTransaction(
            ids, UUID.randomUUID(), java.time.Instant.now()));

    ArgumentCaptor<java.util.Collection<UUID>> batches = ArgumentCaptor.captor();
    verify(gameItemRepository, times(3)).restoreKnownWikiItems(batches.capture(), any());
    assertEquals(
        ids,
        batches.getAllValues().stream()
            .flatMap(java.util.Collection::stream)
            .collect(Collectors.toSet()));
    org.junit.jupiter.api.Assertions.assertTrue(
        batches.getAllValues().stream().allMatch(batch -> !batch.isEmpty() && batch.size() <= 500));
  }

  /**
   * Stubs the 5-arg {@code fetchAllPages} for a single endpoint to return exactly the given row.
   *
   * @param endpoint the endpoint to stub
   * @param row the single row the pass returns
   */
  private void stubPass(String endpoint, ScWikiItemDto row) {
    lenient()
        .when(scWikiClient.fetchAllPagesResult(eq(endpoint), any(), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(row)));
  }

  /**
   * Stubs one endpoint to return several rows as a <em>complete</em> census. Used for the residual
   * {@code /api/items} pass, which has to enumerate the whole pool for the sweep-gating tests to
   * mean anything.
   *
   * @param endpoint the endpoint to stub
   * @param rows the rows the pass returns, in order
   */
  private void stubPassRows(String endpoint, ScWikiItemDto... rows) {
    lenient()
        .when(scWikiClient.fetchAllPagesResult(eq(endpoint), any(), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(rows)));
  }

  /**
   * Stubs one endpoint to return rows the page walk could <em>not</em> vouch for — the {@code
   * FetchResult.partial} shape a repeated row or a shortfall against {@code meta.total} produces
   * (ADR-0147). The rows are still ingested; only the census claim is withheld.
   *
   * @param endpoint the endpoint to stub
   * @param rows the rows the incomplete walk managed to accumulate
   */
  private void stubPartialPass(String endpoint, ScWikiItemDto... rows) {
    lenient()
        .when(scWikiClient.fetchAllPagesResult(eq(endpoint), any(), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.partial(List.of(rows)));
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
        null,
        "common",
        1.0,
        null,
        manufacturer,
        Map.of("en_EN", "desc"),
        Boolean.TRUE,
        Boolean.FALSE,
        "4.8.0-LIVE",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
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
   * Builds a detached uuid-less {@code UEX_ONLY} game item, the target of the name/slug
   * reconciliation, with the given id, canonical name and {@code uex_slug}.
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
