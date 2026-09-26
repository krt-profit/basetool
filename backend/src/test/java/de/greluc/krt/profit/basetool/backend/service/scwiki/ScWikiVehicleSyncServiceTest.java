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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiVehicleDto;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.service.SyncReportService;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
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

/** Unit tests for {@link ScWikiVehicleSyncService} — the R4 Wiki vehicle fill. */
@ExtendWith(MockitoExtension.class)
class ScWikiVehicleSyncServiceTest {

  @Mock private ScWikiClient scWikiClient;
  @Mock private ShipTypeRepository shipTypeRepository;
  @Mock private SyncReportService syncReportService;

  private ScWikiProperties properties;

  /** The configured keys, relative to the record's prefix; {@link #rebuild()} binds them. */
  private final Map<String, Object> config = new HashMap<>();

  private ScWikiVehicleSyncService service;

  @BeforeEach
  void setUp() {
    config.putAll(Map.of("vehicle-sync-enabled", true));
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
        new ScWikiVehicleSyncService(
            scWikiClient, properties, shipTypeRepository, syncReportService);
  }

  @Test
  void syncVehicles_isNoOp_whenFeatureFlagOff() {
    config.put("vehicle-sync-enabled", false);
    rebuild();

    service.syncVehicles();

    verifyNoInteractions(scWikiClient, shipTypeRepository);
  }

  @Test
  void syncVehicles_requestsTheVehicleSpecificPageSize() {
    config.put("vehicles-page-size", 50);
    rebuild();
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of()));

    service.syncVehicles();

    verify(scWikiClient)
        .fetchAllPagesResult(any(), any(), eq("vehicles"), isNull(), isNull(), eq(50));
  }

  @Test
  void syncVehicles_fillsWikiColumns_flipsUexOnlyToBoth_andLeavesUexFieldsUntouched() {
    UUID uuid = UUID.randomUUID();
    ShipType uexShip = new ShipType();
    uexShip.setId(UUID.randomUUID());
    uexShip.setName("100i");
    uexShip.setExternalUuid(uuid);
    uexShip.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
    uexShip.setIsBomber(true);
    uexShip.setDescriptionEn("UEX English desc");

    ScWikiVehicleDto dto =
        new ScWikiVehicleDto(
            uuid,
            "orig-100i",
            "100i",
            "Origin 100i game",
            "ORIG_100i",
            2.0,
            Map.of("en_EN", "Wiki English desc", "de_DE", "Wiki Deutsch"));

    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(shipTypeRepository.findByExternalUuid(uuid)).thenReturn(Optional.of(uexShip));
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));
    when(shipTypeRepository.markScwikiDeletedExcept(any(), any())).thenReturn(0);

    service.syncVehicles();

    ArgumentCaptor<ShipType> saved = ArgumentCaptor.forClass(ShipType.class);
    verify(shipTypeRepository).save(saved.capture());
    ShipType result = saved.getValue();
    assertEquals(GameItemSourceSystem.BOTH, result.getSourceSystems());
    assertEquals("orig-100i", result.getScwikiSlug());
    assertEquals("Origin 100i game", result.getGameName());
    assertEquals("ORIG_100i", result.getClassName());
    assertEquals(2.0, result.getVehicleInventoryScu());
    assertEquals("Wiki Deutsch", result.getDescriptionDe());
    assertEquals("UEX English desc", result.getDescriptionEn());
    assertTrue(result.getIsBomber());
  }

  @Test
  void syncVehicles_createsWikiOnly_whenNoLocalMatch() {
    UUID uuid = UUID.randomUUID();
    ScWikiVehicleDto dto =
        new ScWikiVehicleDto(
            uuid, "wiki-only-ship", "Wiki Only Ship", null, null, null, Map.of("en_EN", "x"));
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(shipTypeRepository.findByExternalUuid(uuid)).thenReturn(Optional.empty());
    when(shipTypeRepository.findByNameIgnoreCase("Wiki Only Ship")).thenReturn(Optional.empty());
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));
    when(shipTypeRepository.markScwikiDeletedExcept(any(), any())).thenReturn(0);

    service.syncVehicles();

    ArgumentCaptor<ShipType> saved = ArgumentCaptor.forClass(ShipType.class);
    verify(shipTypeRepository).save(saved.capture());
    assertEquals(GameItemSourceSystem.WIKI_ONLY, saved.getValue().getSourceSystems());
    assertEquals(uuid, saved.getValue().getExternalUuid());
  }

  @Test
  void syncVehicles_nameFallback_backfillsExternalUuid() {
    UUID uuid = UUID.randomUUID();
    ShipType legacy = new ShipType();
    legacy.setId(UUID.randomUUID());
    legacy.setName("Cutlass Black");
    legacy.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
    ScWikiVehicleDto dto =
        new ScWikiVehicleDto(uuid, "drake-cutlass", "Cutlass Black", null, null, null, null);
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(shipTypeRepository.findByExternalUuid(uuid)).thenReturn(Optional.empty());
    when(shipTypeRepository.findByNameIgnoreCase("Cutlass Black")).thenReturn(Optional.of(legacy));
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));
    when(shipTypeRepository.markScwikiDeletedExcept(any(), any())).thenReturn(0);

    service.syncVehicles();

    ArgumentCaptor<ShipType> saved = ArgumentCaptor.forClass(ShipType.class);
    verify(shipTypeRepository).save(saved.capture());
    assertEquals(
        uuid, saved.getValue().getExternalUuid(), "name match must backfill external_uuid");
    assertEquals(GameItemSourceSystem.BOTH, saved.getValue().getSourceSystems());
  }

  @Test
  void syncVehicles_emptyResponse_skipsOrphanSweep() {
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of()));

    int written = service.syncVehicles();

    verify(shipTypeRepository, never()).markScwikiDeletedExcept(any(), any());
    verify(shipTypeRepository, never()).save(any());
    assertEquals(0, written, "an empty Wiki response must report zero written rows");
  }

  @Test
  void syncVehicles_notModified_reportsLiveCount_andSkipsSyncAndSweep() {
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.unchanged());
    when(shipTypeRepository.countLiveScwikiShipTypes()).thenReturn(42L);

    int written = service.syncVehicles();

    assertEquals(42, written, "a 304 (unchanged) catalogue must report the live ship_type count");
    verify(shipTypeRepository, never()).save(any());
    verify(shipTypeRepository, never()).markScwikiDeletedExcept(any(), any());
  }

  @Test
  void syncVehicles_incompletePageWalk_upsertsTheRows_butSkipsOrphanSweep() {
    UUID uuid = UUID.randomUUID();
    ScWikiVehicleDto dto =
        new ScWikiVehicleDto(uuid, "drake-caterpillar", "Caterpillar", null, null, null, null);
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.partial(List.of(dto)));
    when(shipTypeRepository.findByExternalUuid(uuid)).thenReturn(Optional.empty());
    when(shipTypeRepository.findByNameIgnoreCase("Caterpillar")).thenReturn(Optional.empty());
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));

    int written = service.syncVehicles();

    verify(shipTypeRepository).save(any(ShipType.class));
    verify(shipTypeRepository, never()).markScwikiDeletedExcept(any(), any());
    assertEquals(1, written, "the rows that arrived are still written");
  }
}
