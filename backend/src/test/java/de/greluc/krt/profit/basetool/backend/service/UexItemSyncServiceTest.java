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

  @InjectMocks private UexItemSyncService service;

  private UexCategory helmetsCategory;
  private UexCategory liveriesCategory;
  private Manufacturer rsi;

  @BeforeEach
  void setUp() {
    // self.getObject() must return the real instance so the per-item REQUIRES_NEW upsert runs its
    // actual logic; lenient() because tests that fetch no items never enter the loop.
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
    when(manufacturerAliasRepository.findManufacturerByUexCompanyId(1))
        .thenReturn(Optional.of(rsi));
    when(gameItemRepository.save(any(GameItem.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncItems();

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
    // Wiki columns stay untouched (R2 does not write them).
    assertNull(persisted.getScwikiSyncedAt());
    assertNull(persisted.getDescriptionEn());
  }

  @Test
  void syncItems_handlesEmptyUuidByLeavingExternalUuidNull() {
    UexItemDto avionics = helmetDto(99, "Random Flight Blade", "", helmetsCategory);
    // shift category section to Avionics for this case
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
    // The prod failure on item 4752 ("Pulse Greycat Laser Pistol"): UEX gives a base weapon and its
    // skins ONE shared in-game uuid, but external_uuid is UNIQUE. This skin already has its own row
    // (resolved by uex_item_id, external_uuid still null); the base weapon's row already owns the
    // shared uuid. Backfilling it onto the skin would hit uk_game_item_external_uuid. The guard
    // must
    // leave external_uuid null and sync every other column instead of throwing.
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

    // The decline is expected steady state (no per-item WARN, #1205): the run summary surfaces it
    // as
    // a single aggregate count so prod keeps visibility without one warning per sync per sibling.
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
    // A paint Wiki filed as VEHICLE_ITEM (via /vehicle-items); UEX later lists the same
    // external_uuid under the "Liveries" section (deriveKind → GENERIC). The §6.3.1
    // more-specific-wins merge must keep VEHICLE_ITEM — UEX must not downgrade it to GENERIC.
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
    // Zero upserts is the tally fed to basetool_scheduled_job_items_total{job=uex_sync} — a UEX
    // catalogue outage returning empty 200 responses shows here as 0 (#1041 item 2, SyncZeroItems).
    assertEquals(0, upserted, "an empty UEX catalogue must report zero upserts");
  }

  @Test
  void syncItems_reportsLiveCatalogueSize_whenCatalogueUnchanged304() {
    // Healthy no-op: UEX serves every category from the conditional-GET cache (304 Not Modified),
    // so
    // the run upserts nothing. Reporting 0 would be indistinguishable from the empty-200 outage
    // SyncZeroItems targets, so the run reports the live catalogue size instead — keeping
    // basetool_scheduled_job_items_total{job=uex_sync} non-zero on an unchanged catalogue.
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
    // A genuine empty-200 outage (no 304 anywhere) must still report 0 so SyncZeroItems fires — the
    // live-catalogue fallback must never mask a real outage.
    when(categoryRefService.syncCategories()).thenReturn(List.of(helmetsCategory));
    when(uexClient.getItemsForCategory(3)).thenReturn(fetched());

    int reported = service.syncItems();

    assertEquals(
        0, reported, "an empty-200 catalogue outage must report zero, not the catalogue size");
    verify(gameItemRepository, never()).countLiveUexItems();
  }

  @Test
  void syncItems_skipsOrphanSweep_whenAnyCategoryUnchanged304_evenWithFreshItems() {
    // Mixed run: category 3 returns a fresh item, category 75 is unchanged (304). seenUexItemIds
    // then holds only category 3's item — an INCOMPLETE view of the catalogue. Running the orphan
    // sweep would wrongly soft-delete category 75's cached items, so it must be skipped this run.
    // The upsert count still reflects the fresh item (non-zero), so the catalogue-size fallback for
    // the all-unchanged case does not apply.
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
    // One item upserted → the count reported to basetool_scheduled_job_items_total{job=uex_sync}
    // (#1041 item 2).
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

    // Exactly one summary row per run (carrying the tally), so the run shows on the admin UEX tab
    // without one event per created item; then the UEX retention sweep runs.
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
    // Regression for the shipped prod incident (3376 cascade failures, no "Finished" summary): the
    // item loop used to run in ONE transaction, so a single uk_game_item_external_uuid collision
    // poisoned the Hibernate session and every SUBSEQUENT item's autoflush re-threw the dead
    // insert, rolling back the whole run. With per-item REQUIRES_NEW isolation the caller's catch
    // skips only the colliding row; the rest of the category still commits and the summary writes.
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

    // Both items are attempted (the loop never aborts); only the surviving row commits.
    verify(gameItemRepository, times(2)).save(any(GameItem.class));
    verify(gameItemRepository).save(argThat(g -> Integer.valueOf(200).equals(g.getUexItemId())));
    assertEquals(1, upserted, "the failing item must not be counted, the surviving one must be");
    // The orphan sweep still runs, keyed only on the surviving id — the collided id never entered
    // the seen-set, so a poisoned run does not wipe the local catalogue.
    verify(gameItemRepository)
        .markUexDeletedExcept(
            argThat(ids -> ids.contains(200) && !ids.contains(100)), any(Instant.class));
    // The run summary is still emitted despite the mid-loop failure (the missing "Finished" line
    // was the prod symptom).
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
    // Pin the REQUIRES_NEW propagation by reflection: it is the whole mechanism that keeps a
    // uk_game_item_external_uuid collision from poisoning the run's session. Demoting it to the
    // default REQUIRED (or dropping @Transactional) silently reintroduces the whole-run rollback.
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
  }

  @Test
  void syncItems_keepsExistingExternalUuid_whenUexShipsADifferentUuid() {
    // A local row already carries external_uuid=A (e.g. written by the Wiki side). UEX later
    // ships a valid but DIFFERENT uuid=B for the same uex_item_id. The guard must keep the
    // existing key A: overwriting it would silently repoint the game_item to a different in-game
    // asset and break every blueprint-ingredient / item-price join keyed on external_uuid.
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
    // Every other UEX column still syncs from the DTO.
    assertEquals("Ballistic Helmet", persisted.getName(), "name still updates");
    assertEquals("ballistic-helmet", persisted.getUexSlug(), "slug still updates");
    assertEquals(500, persisted.getUexItemId());
    assertNotNull(persisted.getUexSyncedAt(), "uex_synced_at still stamped");
    // The incoming uuid B is never resolved as an owner — the row was matched by uex_item_id.
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
    return new UexClient.FetchResult<>(List.of(items), false);
  }

  /**
   * A {@code 304 Not Modified} fetch result — empty data flagged {@code notModified = true}, the
   * shape {@link UexClient#getItemsForCategory(int)} returns when the category is unchanged since
   * the last sync and served from the conditional-GET cache.
   *
   * @return an empty {@link UexClient.FetchResult} flagged {@code notModified = true}
   */
  private static UexClient.FetchResult<UexItemDto> unchanged() {
    return new UexClient.FetchResult<>(List.of(), true);
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
