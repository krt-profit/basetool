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

import static de.greluc.krt.profit.basetool.backend.service.UexRefs.ref;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.dto.uex.UexItemDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.GameItemKind;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.UexCategory;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerUexCompanyRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Unit tests for {@link UexItemSyncService}. */
@ExtendWith(MockitoExtension.class)
class UexItemSyncServiceTest {

  @Mock private UexClient uexClient;
  @Mock private UexCategoryRefService categoryRefService;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private ManufacturerRepository manufacturerRepository;
  @Mock private ManufacturerUexCompanyRepository manufacturerAliasRepository;
  @Mock private ShipTypeRepository shipTypeRepository;
  @Mock private SyncReportService syncReportService;
  @Mock private ObjectProvider<UexItemSyncService> self;

  /** A real chunk writer, so the rows are actually written through its callbacks (BE-PERF-09). */
  @Spy private SyncChunkWriter chunkWriter = new SyncChunkWriter(new RecordingTransactionManager());

  @InjectMocks private UexItemSyncService service;

  private UexCategory helmetsCategory;
  private UexCategory liveriesCategory;
  private Manufacturer rsi;

  @BeforeEach
  void setUp() {
    lenient().when(self.getObject()).thenReturn(service);

    helmetsCategory = new UexCategory();
    helmetsCategory.setId(3);
    helmetsCategory.setType("item");
    helmetsCategory.setSection("Armor");
    helmetsCategory.setName("Helmets");
    helmetsCategory.setIsGameRelated(true);
    helmetsCategory.setIsMining(false);

    liveriesCategory = new UexCategory();
    liveriesCategory.setId(75);
    liveriesCategory.setType("item");
    liveriesCategory.setSection("Liveries");
    liveriesCategory.setName("Paints");
    liveriesCategory.setIsGameRelated(true);
    liveriesCategory.setIsMining(false);

    rsi = new Manufacturer();
    rsi.setId(UUID.randomUUID());
    rsi.setName("Roberts Space Industries");
    rsi.setUexCompanyId(1);
  }

  @Test
  void syncItems_persistsUexColumnsAndStampsUexOnlySource_whenUUIDPresent() {
    UexItemDto helmet =
        new UexItemDto(
            42,
            0,
            3,
            1,
            0,
            "Venture Helmet White",
            "venture-helmet-white-2",
            "28c76343-8da9-495a-9339-3d5de02e6c3c",
            "1",
            "white",
            null,
            0,
            "https://example.com/store",
            "Armor",
            "Helmets",
            "Roberts Space Industries",
            null,
            "https://example.com/shot.png",
            0,
            0,
            0,
            0,
            0,
            "4.8.0-LIVE",
            123L,
            456L,
            null);

    when(categoryRefService.syncCategories()).thenReturn(List.of(helmetsCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched(helmet));
    when(gameItemRepository.findByUexItemId(42)).thenReturn(Optional.empty());
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(manufacturerAliasRepository.findCompanyRefs()).thenReturn(List.of(ref(1, rsi.getId())));
    when(manufacturerRepository.getReferenceById(rsi.getId())).thenReturn(rsi);
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncItems();

    verify(manufacturerAliasRepository, never()).findManufacturerByUexCompanyId(any());

    ArgumentCaptor<GameItem> saved = ArgumentCaptor.forClass(GameItem.class);
    verify(gameItemRepository).save(saved.capture());
    GameItem persisted = saved.getValue();
    assertEquals(GameItemKind.ARMOR, persisted.getKind());
    assertEquals(GameItemSourceSystem.UEX_ONLY, persisted.getSourceSystems());
    assertEquals(
        UUID.fromString("28c76343-8da9-495a-9339-3d5de02e6c3c"), persisted.getExternalUuid());
    assertEquals(42, persisted.getUexItemId());
    assertEquals("venture-helmet-white-2", persisted.getUexSlug());
    assertSame(rsi, persisted.getManufacturer());
    assertEquals("Venture Helmet White", persisted.getName());
    assertNotNull(persisted.getUexSyncedAt());
    assertNull(persisted.getScwikiSyncedAt());
    assertNull(persisted.getDescriptionEn());
  }

  @Test
  void syncItems_handlesEmptyUuidByLeavingExternalUuidNull() {
    UexItemDto avionics = helmetDto(99, "Random Flight Blade", "", helmetsCategory);
    helmetsCategory.setSection("Avionics");
    helmetsCategory.setName("Flight Blade");

    when(categoryRefService.syncCategories()).thenReturn(List.of(helmetsCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched(avionics));
    when(gameItemRepository.findByUexItemId(99)).thenReturn(Optional.empty());
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncItems();

    ArgumentCaptor<GameItem> saved = ArgumentCaptor.forClass(GameItem.class);
    verify(gameItemRepository).save(saved.capture());
    GameItem persisted = saved.getValue();
    assertNull(persisted.getExternalUuid());
    assertEquals(99, persisted.getUexItemId());
    assertEquals(GameItemKind.VEHICLE_ITEM, persisted.getKind());
  }

  @Test
  void syncItems_promotesWikiOnlyToBothWhenUexLandsOnExistingExternalUuid() {
    UUID externalUuid = UUID.randomUUID();
    UexItemDto helmet = helmetDto(7, "Existing Helmet", externalUuid.toString(), helmetsCategory);

    GameItem prior = new GameItem();
    prior.setId(UUID.randomUUID());
    prior.setExternalUuid(externalUuid);
    prior.setName("Existing Helmet");
    prior.setKind(GameItemKind.ARMOR);
    prior.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    prior.setScwikiSyncedAt(java.time.Instant.now());

    when(categoryRefService.syncCategories()).thenReturn(List.of(helmetsCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched(helmet));
    when(gameItemRepository.findByUexItemId(7)).thenReturn(Optional.empty());
    when(gameItemRepository.findByExternalUuid(externalUuid)).thenReturn(Optional.of(prior));
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncItems();

    ArgumentCaptor<GameItem> saved = ArgumentCaptor.forClass(GameItem.class);
    verify(gameItemRepository).save(saved.capture());
    assertEquals(GameItemSourceSystem.BOTH, saved.getValue().getSourceSystems());
    assertNotNull(saved.getValue().getScwikiSyncedAt(), "Wiki timestamp must be preserved");
  }

  @Test
  void syncItems_leavesExternalUuidNull_whenIncomingUuidAlreadyOwnedByAnotherRow() {
    UUID sharedUuid = UUID.randomUUID();
    UexItemDto skin =
        helmetDto(4752, "Pulse Greycat Laser Pistol", sharedUuid.toString(), helmetsCategory);

    GameItem skinRow = new GameItem();
    skinRow.setId(UUID.randomUUID());
    skinRow.setUexItemId(4752);
    skinRow.setExternalUuid(null);
    skinRow.setName("Pulse Greycat Laser Pistol (stale)");
    skinRow.setKind(GameItemKind.GENERIC);
    skinRow.setSourceSystems(GameItemSourceSystem.UEX_ONLY);

    GameItem uuidOwner = new GameItem();
    uuidOwner.setId(UUID.randomUUID());
    uuidOwner.setUexItemId(879);
    uuidOwner.setExternalUuid(sharedUuid);

    when(categoryRefService.syncCategories()).thenReturn(List.of(helmetsCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched(skin));
    when(gameItemRepository.findByUexItemId(4752)).thenReturn(Optional.of(skinRow));
    when(gameItemRepository.findByExternalUuid(sharedUuid)).thenReturn(Optional.of(uuidOwner));
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));

    assertDoesNotThrow(service::syncItems);

    ArgumentCaptor<GameItem> saved = ArgumentCaptor.forClass(GameItem.class);
    verify(gameItemRepository).save(saved.capture());
    GameItem persisted = saved.getValue();
    assertSame(skinRow, persisted, "must reuse the skin's own row, not the uuid owner's");
    assertNull(
        persisted.getExternalUuid(), "must not claim a uuid another game_item row already owns");
    assertEquals(4752, persisted.getUexItemId());
    assertEquals("Pulse Greycat Laser Pistol", persisted.getName(), "other columns still sync");
    assertEquals(GameItemKind.ARMOR, persisted.getKind());

    verify(syncReportService)
        .logUexEvent(
            any(),
            eq(SyncEventType.SYNC_RUN_SUMMARY),
            eq("game_item"),
            isNull(),
            isNull(),
            contains("sharedUuidDeclined=1"));
  }

  @Test
  void syncItems_doesNotDowngradeKindToGeneric_whenUexReCataloguesAWikiSpecificRow() {
    UUID externalUuid = UUID.randomUUID();
    UexItemDto paint =
        helmetDto(21, "100i Auspicious Red Dog Livery", externalUuid.toString(), liveriesCategory);

    GameItem prior = new GameItem();
    prior.setId(UUID.randomUUID());
    prior.setExternalUuid(externalUuid);
    prior.setName("100i Auspicious Red Dog Livery");
    prior.setKind(GameItemKind.VEHICLE_ITEM);
    prior.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    prior.setScwikiSyncedAt(java.time.Instant.now());

    when(categoryRefService.syncCategories()).thenReturn(List.of(liveriesCategory));
    when(uexClient.getItemsForCategory(75)).thenReturn(fetched(paint));
    when(gameItemRepository.findByUexItemId(21)).thenReturn(Optional.empty());
    when(gameItemRepository.findByExternalUuid(externalUuid)).thenReturn(Optional.of(prior));
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncItems();

    ArgumentCaptor<GameItem> saved = ArgumentCaptor.forClass(GameItem.class);
    verify(gameItemRepository).save(saved.capture());
    assertEquals(
        GameItemKind.VEHICLE_ITEM,
        saved.getValue().getKind(),
        "UEX must not downgrade a Wiki-set VEHICLE_ITEM to GENERIC (§6.3.1)");
  }

  @Test
  void syncItems_skipsOrphanSweep_whenNoItemsWereProcessed() {
    when(categoryRefService.syncCategories()).thenReturn(List.of(helmetsCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched());

    int upserted = service.syncItems();

    verify(gameItemRepository, never()).markUexDeletedExcept(any(), any());
    assertEquals(0, upserted, "an empty UEX catalogue must report zero upserts");
  }

  @Test
  void syncItems_reportsLiveCatalogueSize_whenCatalogueUnchanged304() {
    when(categoryRefService.syncCategories()).thenReturn(List.of(helmetsCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(unchanged());
    when(gameItemRepository.countLiveUexItems()).thenReturn(4200L);

    int reported = service.syncItems();

    assertEquals(
        4200, reported, "an unchanged (all-304) catalogue must report its live size, not 0");
    verify(gameItemRepository, never()).save(any(GameItem.class));
    verify(gameItemRepository, never()).markUexDeletedExcept(any(), any());
  }

  @Test
  void syncItems_reportsZeroWithoutCatalogueFallback_whenEmpty200Outage() {
    when(categoryRefService.syncCategories()).thenReturn(List.of(helmetsCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched());

    int reported = service.syncItems();

    assertEquals(
        0, reported, "an empty-200 catalogue outage must report zero, not the catalogue size");
    verify(gameItemRepository, never()).countLiveUexItems();
  }

  @Test
  void syncItems_skipsOrphanSweep_whenAnyCategoryUnchanged304_evenWithFreshItems() {
    UexItemDto helmet =
        helmetDto(11, "Venture Helmet", UUID.randomUUID().toString(), helmetsCategory);
    when(categoryRefService.syncCategories())
        .thenReturn(List.of(helmetsCategory, liveriesCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched(helmet));
    when(uexClient.getItemsForCategory(75)).thenReturn(unchanged());
    when(gameItemRepository.findByUexItemId(anyInt())).thenReturn(Optional.empty());
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));

    int upserted = service.syncItems();

    verify(gameItemRepository, never()).markUexDeletedExcept(any(), any());
    verify(gameItemRepository, never()).countLiveUexItems();
    assertEquals(
        1, upserted, "the fresh item is counted; the 304 category defers the orphan sweep");
  }

  @Test
  void syncItems_skipsOrphanSweep_whenACategoryFetchFailed_evenWithFreshItems() {
    UexItemDto helmet =
        helmetDto(11, "Venture Helmet", UUID.randomUUID().toString(), helmetsCategory);
    when(categoryRefService.syncCategories())
        .thenReturn(List.of(helmetsCategory, liveriesCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched(helmet));
    when(uexClient.getItemsForCategory(75)).thenReturn(failed());
    when(gameItemRepository.findByUexItemId(anyInt())).thenReturn(Optional.empty());
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));

    int upserted = service.syncItems();

    verify(gameItemRepository, never()).markUexDeletedExcept(any(), any());
    assertEquals(
        1, upserted, "the fresh item is still ingested; only the orphan sweep stands down");
  }

  @Test
  void syncItems_runsOrphanSweep_whenAnEmptyCategoryAnsweredCompletely() {
    UexItemDto helmet =
        helmetDto(11, "Venture Helmet", UUID.randomUUID().toString(), helmetsCategory);
    when(categoryRefService.syncCategories())
        .thenReturn(List.of(helmetsCategory, liveriesCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched(helmet));
    when(uexClient.getItemsForCategory(75)).thenReturn(fetched());
    when(gameItemRepository.findByUexItemId(anyInt())).thenReturn(Optional.empty());
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));
    when(gameItemRepository.markUexDeletedExcept(any(), any())).thenReturn(0);

    service.syncItems();

    verify(gameItemRepository).markUexDeletedExcept(any(), any());
  }

  @Test
  void syncItems_runsOrphanSweep_whenAtLeastOneItemProcessed() {
    UexItemDto helmet =
        helmetDto(11, "Venture Helmet", UUID.randomUUID().toString(), helmetsCategory);
    when(categoryRefService.syncCategories()).thenReturn(List.of(helmetsCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched(helmet));
    when(gameItemRepository.findByUexItemId(anyInt())).thenReturn(Optional.empty());
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));
    when(gameItemRepository.markUexDeletedExcept(any(), any())).thenReturn(0);

    int upserted = service.syncItems();

    verify(gameItemRepository).markUexDeletedExcept(any(), any());
    assertEquals(1, upserted, "one processed item must report one upsert");
  }

  @Test
  void syncItems_writesOneUexRunSummaryEvent_andPrunesUexRuns() {
    UexItemDto helmet =
        helmetDto(11, "Venture Helmet", UUID.randomUUID().toString(), helmetsCategory);
    when(categoryRefService.syncCategories()).thenReturn(List.of(helmetsCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched(helmet));
    when(gameItemRepository.findByUexItemId(anyInt())).thenReturn(Optional.empty());
    when(gameItemRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));
    when(gameItemRepository.markUexDeletedExcept(any(), any())).thenReturn(0);

    service.syncItems();

    verify(syncReportService)
        .logUexEvent(
            any(),
            eq(SyncEventType.SYNC_RUN_SUMMARY),
            eq("game_item"),
            isNull(),
            isNull(),
            contains("created=1"));
    verify(syncReportService).pruneRuns(SyncSourceSystem.UEX);
  }

  @Test
  void deriveKind_armorSection_returnsArmor() {
    UexCategory armor = newCategory("Armor", "Helmets");
    assertEquals(GameItemKind.ARMOR, UexItemSyncService.deriveKind(armor));
  }

  @Test
  void deriveKind_personalWeaponsWithAttachmentsName_returnsWeaponAttachment() {
    UexCategory attachments = newCategory("Personal Weapons", "Attachments");
    assertEquals(GameItemKind.WEAPON_ATTACHMENT, UexItemSyncService.deriveKind(attachments));
  }

  @Test
  void deriveKind_personalWeaponsRifles_returnsWeapon() {
    UexCategory rifles = newCategory("Personal Weapons", "Rifles");
    assertEquals(GameItemKind.WEAPON, UexItemSyncService.deriveKind(rifles));
  }

  @Test
  void deriveKind_vehicleWeapons_returnsVehicleWeapon() {
    UexCategory weapons = newCategory("Vehicle Weapons", "Guns");
    assertEquals(GameItemKind.VEHICLE_WEAPON, UexItemSyncService.deriveKind(weapons));
  }

  @Test
  void deriveKind_systemsAvionicsUtility_returnsVehicleItem() {
    assertEquals(
        GameItemKind.VEHICLE_ITEM,
        UexItemSyncService.deriveKind(newCategory("Systems", "Coolers")));
    assertEquals(
        GameItemKind.VEHICLE_ITEM,
        UexItemSyncService.deriveKind(newCategory("Avionics", "Flight Blade")));
    assertEquals(
        GameItemKind.VEHICLE_ITEM,
        UexItemSyncService.deriveKind(newCategory("Utility", "Quantum Drives")));
  }

  @Test
  void deriveKind_clothingOrUndersuits_returnsClothing() {
    assertEquals(
        GameItemKind.CLOTHING, UexItemSyncService.deriveKind(newCategory("Clothing", "Jackets")));
    assertEquals(
        GameItemKind.CLOTHING,
        UexItemSyncService.deriveKind(newCategory("Undersuits", "Standard")));
  }

  @Test
  void deriveKind_unknownSection_returnsGeneric() {
    assertEquals(
        GameItemKind.GENERIC,
        UexItemSyncService.deriveKind(newCategory("MysterySection", "Whatever")));
  }

  @Test
  void syncItems_isolatesPerItem_oneFailingItemDoesNotAbortTheCategory() {
    UexItemDto colliding = helmetDto(100, "Colliding Helmet", "", helmetsCategory);
    UexItemDto surviving = helmetDto(200, "Surviving Helmet", "", helmetsCategory);

    when(categoryRefService.syncCategories()).thenReturn(List.of(helmetsCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched(colliding, surviving));
    when(gameItemRepository.findByUexItemId(anyInt())).thenReturn(Optional.empty());
    when(gameItemRepository.save(any(GameItem.class)))
        .thenAnswer(
            inv -> {
              GameItem candidate = inv.getArgument(0);
              if (Integer.valueOf(100).equals(candidate.getUexItemId())) {
                throw new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint"
                        + " \"uk_game_item_external_uuid\"");
              }
              return candidate;
            });

    int upserted = assertDoesNotThrow(service::syncItems);

    verify(gameItemRepository, times(2)).save(any(GameItem.class));
    verify(gameItemRepository).save(argThat(g -> Integer.valueOf(200).equals(g.getUexItemId())));
    assertEquals(1, upserted, "the failing item must not be counted, the surviving one must be");
    verify(gameItemRepository)
        .markUexDeletedExcept(
            argThat(ids -> ids.contains(200) && !ids.contains(100)), any(Instant.class));
    verify(syncReportService)
        .logUexEvent(
            any(),
            eq(SyncEventType.SYNC_RUN_SUMMARY),
            eq("game_item"),
            isNull(),
            isNull(),
            contains("upserted=1"));
  }

  @Test
  void upsertItemWithinTransaction_opensItsOwnTransaction() throws NoSuchMethodException {
    Transactional tx =
        UexItemSyncService.class
            .getMethod(
                "upsertItemWithinTransaction", UexItemDto.class, UexCategory.class, Instant.class)
            .getAnnotation(Transactional.class);

    assertNotNull(tx, "the per-item upsert must be transactional");
    assertEquals(
        Propagation.REQUIRES_NEW,
        tx.propagation(),
        "the per-item write must run in its own transaction so a collision isolates to one item");
    Transactional withLookups =
        UexItemSyncService.class
            .getMethod(
                "upsertItemWithinTransaction",
                UexItemDto.class,
                UexCategory.class,
                Instant.class,
                UexItemSyncService.ItemLookups.class)
            .getAnnotation(Transactional.class);
    assertNotNull(withLookups);
    assertEquals(Propagation.REQUIRES_NEW, withLookups.propagation());
  }

  @Test
  void syncItems_keepsExistingExternalUuid_whenUexShipsADifferentUuid() {
    UUID keptUuidA = UUID.randomUUID();
    UUID incomingUuidB = UUID.randomUUID();
    UexItemDto helmet =
        helmetDto(500, "Ballistic Helmet", incomingUuidB.toString(), helmetsCategory);

    GameItem existing = new GameItem();
    existing.setId(UUID.randomUUID());
    existing.setUexItemId(500);
    existing.setExternalUuid(keptUuidA);
    existing.setName("Ballistic Helmet (stale)");
    existing.setKind(GameItemKind.ARMOR);
    existing.setUexSlug("ballistic-helmet-old");
    existing.setSourceSystems(GameItemSourceSystem.BOTH);

    when(categoryRefService.syncCategories()).thenReturn(List.of(helmetsCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched(helmet));
    when(gameItemRepository.findByUexItemId(500)).thenReturn(Optional.of(existing));
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncItems();

    ArgumentCaptor<GameItem> saved = ArgumentCaptor.forClass(GameItem.class);
    verify(gameItemRepository).save(saved.capture());
    GameItem persisted = saved.getValue();
    assertSame(existing, persisted, "must reuse the row resolved by uex_item_id");
    assertEquals(
        keptUuidA,
        persisted.getExternalUuid(),
        "UEX must not overwrite an existing external_uuid with a different one");
    assertEquals("Ballistic Helmet", persisted.getName(), "name still updates");
    assertEquals("ballistic-helmet", persisted.getUexSlug(), "slug still updates");
    assertEquals(500, persisted.getUexItemId());
    assertNotNull(persisted.getUexSyncedAt(), "uex_synced_at still stamped");
    verify(gameItemRepository, never()).findByExternalUuid(incomingUuidB);
  }

  private UexCategory newCategory(String section, String name) {
    UexCategory c = new UexCategory();
    c.setSection(section);
    c.setName(name);
    return c;
  }

  /**
   * Wraps the given item DTOs as a normal {@code 200} fetch result (data present, {@code
   * notModified = false}) — the shape {@link UexClient#getItemsForCategory(int)} returns for a
   * fresh response.
   *
   * @param items the DTOs the category returns (none = an empty-200 response)
   * @return a {@link UexClient.FetchResult} carrying the items with {@code notModified = false}
   */
  private static UexClient.FetchResult<UexItemDto> fetched(UexItemDto... items) {
    return UexClient.FetchResult.of(List.of(items));
  }

  /**
   * A {@code 304 Not Modified} fetch result — empty data flagged {@code notModified = true}, the
   * shape {@link UexClient#getItemsForCategory(int)} returns when the category is unchanged since
   * the last sync and served from the conditional-GET cache.
   *
   * @return an empty {@link UexClient.FetchResult} flagged {@code notModified = true}
   */
  private static UexClient.FetchResult<UexItemDto> unchanged() {
    return UexClient.FetchResult.unchanged();
  }

  /**
   * A swallowed-failure fetch result — empty data, {@code notModified = false}, {@code complete =
   * false}: the shape {@link UexClient#getItemsForCategory(int)} returns when the call timed out,
   * answered non-2xx, failed to decode, or carried a non-ok envelope status. Indistinguishable from
   * an empty category by its rows alone, which is exactly why the flag exists.
   *
   * @return an empty {@link UexClient.FetchResult} flagged incomplete
   */
  private static UexClient.FetchResult<UexItemDto> failed() {
    return UexClient.FetchResult.partial(List.of());
  }

  private UexItemDto helmetDto(int id, String name, String uuid, UexCategory cat) {
    return new UexItemDto(
        id,
        0,
        cat.getId() == null ? 3 : cat.getId(),
        1,
        0,
        name,
        name.toLowerCase().replace(' ', '-'),
        uuid,
        "1",
        null,
        null,
        0,
        null,
        cat.getSection(),
        cat.getName(),
        "Roberts Space Industries",
        null,
        null,
        0,
        0,
        0,
        0,
        0,
        "4.8.0-LIVE",
        0L,
        0L,
        null);
  }
}
