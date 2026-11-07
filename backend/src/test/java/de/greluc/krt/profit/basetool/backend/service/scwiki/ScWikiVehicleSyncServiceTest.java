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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiVehicleSyncService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncChunkWriter;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncEventType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncReportService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncRunProgress;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiVehicleDto;
import de.greluc.krt.profit.basetool.backend.service.RecordingTransactionManager;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
            scWikiClient,
            properties,
            shipTypeRepository,
            syncReportService,
            new SyncChunkWriter(new RecordingTransactionManager(), new SyncRunProgress()));
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
            2_000_000.0,
            Map.of("en_EN", "Wiki English desc", "de_DE", "Wiki Deutsch"),
            null,
            null,
            null,
            null,
            null);

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
  void syncVehicles_linksTheAliasedUexRow_insteadOfCreatingAWikiOnlyTwin() {
    UUID uuid = UUID.randomUUID();
    ShipType uexShip = new ShipType();
    uexShip.setId(UUID.randomUUID());
    uexShip.setName("San tok.Yai");
    uexShip.setUexVehicleId(173);
    uexShip.setSourceSystems(GameItemSourceSystem.UEX_ONLY);
    ScWikiVehicleDto dto =
        new ScWikiVehicleDto(
            uuid,
            "xnaa-santokyai",
            "San'tok.yai",
            null,
            "XNAA_SanTokYai",
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(shipTypeRepository.findByExternalUuid(uuid)).thenReturn(Optional.empty());
    when(shipTypeRepository.findByUexAliasOfClassName("XNAA_SanTokYai"))
        .thenReturn(Optional.of(uexShip));
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));
    when(shipTypeRepository.markScwikiDeletedExcept(any(), any())).thenReturn(0);

    service.syncVehicles();

    ArgumentCaptor<ShipType> saved = ArgumentCaptor.forClass(ShipType.class);
    verify(shipTypeRepository).save(saved.capture());
    ShipType result = saved.getValue();
    assertSame(uexShip, result);
    assertEquals(uuid, result.getExternalUuid());
    assertEquals("XNAA_SanTokYai", result.getClassName());
    assertEquals(GameItemSourceSystem.BOTH, result.getSourceSystems());
    assertEquals("San tok.Yai", result.getName());
    verify(shipTypeRepository, never()).findByNameIgnoreCase(any());
  }

  @Test
  void syncVehicles_createsWikiOnly_whenNoLocalMatch() {
    UUID uuid = UUID.randomUUID();
    ScWikiVehicleDto dto =
        new ScWikiVehicleDto(
            uuid,
            "wiki-only-ship",
            "Wiki Only Ship",
            null,
            null,
            null,
            Map.of("en_EN", "x"),
            null,
            null,
            null,
            null,
            null);
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
        new ScWikiVehicleDto(
            uuid,
            "drake-cutlass",
            "Cutlass Black",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
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
  void repeatedVehiclePayloadsApplyCurrentUnitsAgain() {
    UUID uuid = UUID.randomUUID();
    ShipType existing = new ShipType();
    existing.setExternalUuid(uuid);
    existing.setName("Avenger Stalker");
    var dto =
        ScWikiVehicleDto.builder()
            .uuid(uuid)
            .name("Avenger Stalker")
            .vehicleInventory(710000.0)
            .build();
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(shipTypeRepository.findByExternalUuid(uuid)).thenReturn(Optional.of(existing));
    when(shipTypeRepository.save(any(ShipType.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertEquals(1, service.syncVehicles());
    existing.setVehicleInventoryScu(710000.0);
    assertEquals(1, service.syncVehicles());

    assertEquals(0.71, existing.getVehicleInventoryScu());
    verify(shipTypeRepository, times(2)).save(existing);
  }

  @Test
  void syncVehicles_incompletePageWalk_upsertsTheRows_butSkipsOrphanSweep() {
    UUID uuid = UUID.randomUUID();
    ScWikiVehicleDto dto =
        new ScWikiVehicleDto(
            uuid,
            "drake-caterpillar",
            "Caterpillar",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
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

  @Test
  void syncVehicles_refreshesPreviouslyMisinterpretedInventoryFromCurrentPayload() {
    UUID uuid = UUID.fromString("3e4de942-5611-4f88-a151-89fb6bf6cd26");
    ShipType existing = new ShipType();
    existing.setId(UUID.randomUUID());
    existing.setName("Avenger Stalker");
    existing.setExternalUuid(uuid);
    existing.setVehicleInventoryScu(710000.0);
    var dto =
        ScWikiVehicleDto.builder()
            .uuid(uuid)
            .name("Avenger Stalker")
            .vehicleInventory(710000.0)
            .oreCapacity(12.5)
            .maxMedicalTier("T2")
            .health(11900.5)
            .shieldHp(4488.25)
            .build();
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(shipTypeRepository.findByExternalUuid(uuid)).thenReturn(Optional.of(existing));
    when(shipTypeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    assertEquals(1, service.syncVehicles());

    assertEquals(0.71, existing.getVehicleInventoryScu(), 0.000000001);
    assertEquals(12.5, existing.getOreCapacity());
    assertEquals(2, existing.getMaxMedicalTier());
    assertEquals(11900.5, existing.getHealth());
    assertEquals(4488.25, existing.getShieldHp());
  }

  @Test
  void syncVehicles_rejectsNameMatchWithDifferentUuidAndDoesNotSweep() {
    UUID incomingUuid = UUID.randomUUID();
    UUID storedUuid = UUID.randomUUID();
    ShipType existing = new ShipType();
    existing.setExternalUuid(storedUuid);
    existing.setName("Distinct variant");
    var dto = ScWikiVehicleDto.builder().uuid(incomingUuid).name("Distinct variant").build();
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(shipTypeRepository.findByNameIgnoreCase("Distinct variant"))
        .thenReturn(Optional.of(existing));

    assertEquals(0, service.syncVehicles());

    assertEquals(storedUuid, existing.getExternalUuid());
    verify(shipTypeRepository, never()).save(any());
    verify(shipTypeRepository, never()).markScwikiDeletedExcept(any(), any());
  }

  @Test
  void syncVehicles_rejectsAliasedRowWithDifferentUuid() {
    ShipType existing = new ShipType();
    UUID original = UUID.randomUUID();
    existing.setExternalUuid(original);
    var dto =
        ScWikiVehicleDto.builder()
            .uuid(UUID.randomUUID())
            .name("Utility")
            .className("ESPR_Prowler_Utility")
            .build();
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(shipTypeRepository.findByUexAliasOfClassName(dto.className()))
        .thenReturn(Optional.of(existing));

    assertEquals(0, service.syncVehicles());

    assertEquals(original, existing.getExternalUuid());
    verify(shipTypeRepository, never()).save(any());
    verify(shipTypeRepository, never()).findByNameIgnoreCase(any());
  }

  @Test
  void allAssetCensusRestoresExcludedExistingVehiclesWithoutImportingTheirMetadata() {
    UUID relevant = UUID.randomUUID();
    UUID excluded = UUID.randomUUID();
    UUID absent = UUID.randomUUID();
    singleVehicleCensus(relevant);
    when(shipTypeRepository.findWikiLinkedVehicleUuids())
        .thenReturn(List.of(relevant, excluded, absent));
    when(scWikiClient.fetchExistenceCensus("/api/vehicles", "vehicle existence", 50))
        .thenReturn(new ScWikiClient.ExistenceCensus(Set.of(relevant, excluded), true));
    when(shipTypeRepository.restoreWikiPresentVehicles(eq(List.of(excluded)), any())).thenReturn(1);

    assertEquals(2, service.syncVehicles());

    verify(shipTypeRepository).restoreWikiPresentVehicles(eq(List.of(excluded)), any());
    verify(shipTypeRepository)
        .markScwikiDeletedExcept(
            argThat(ids ->
                ids.size() == 2
                    && ids.contains(relevant)
                    && ids.contains(excluded)
                    && !ids.contains(absent)),
            any());
    verify(shipTypeRepository, times(1)).save(any());
    verify(shipTypeRepository, never()).findByExternalUuid(excluded);
    verify(shipTypeRepository, never()).findByExternalUuid(absent);
  }

  @Test
  void incompleteAllAssetCensusCannotRestoreOrTombstoneExcludedVehicles() {
    UUID relevant = UUID.randomUUID();
    UUID excluded = UUID.randomUUID();
    singleVehicleCensus(relevant);
    when(shipTypeRepository.findWikiLinkedVehicleUuids()).thenReturn(List.of(relevant, excluded));
    when(scWikiClient.fetchExistenceCensus("/api/vehicles", "vehicle existence", 50))
        .thenReturn(new ScWikiClient.ExistenceCensus(Set.of(relevant), false));

    assertEquals(1, service.syncVehicles());

    verify(shipTypeRepository, never()).restoreWikiPresentVehicles(any(), any());
    verify(shipTypeRepository, never()).markScwikiDeletedExcept(any(), any());
    verify(syncReportService).recordIncomplete();
    verify(syncReportService).recordSweep(false);
  }

  @Test
  void allAssetCensusMustContainEveryRelevantVehicleBeforeItCanProveAnAbsence() {
    UUID relevant = UUID.randomUUID();
    UUID excluded = UUID.randomUUID();
    singleVehicleCensus(relevant);
    when(shipTypeRepository.findWikiLinkedVehicleUuids()).thenReturn(List.of(relevant, excluded));
    when(scWikiClient.fetchExistenceCensus("/api/vehicles", "vehicle existence", 50))
        .thenReturn(new ScWikiClient.ExistenceCensus(Set.of(excluded), true));

    assertEquals(1, service.syncVehicles());

    verify(shipTypeRepository, never()).restoreWikiPresentVehicles(any(), any());
    verify(shipTypeRepository, never()).markScwikiDeletedExcept(any(), any());
    verify(syncReportService).recordIncomplete();
  }

  @Test
  void relevantCensusNeedsNoBroaderFetchWhenItContainsEveryPreviouslyLinkedVehicle() {
    UUID relevant = UUID.randomUUID();
    singleVehicleCensus(relevant);
    when(shipTypeRepository.findWikiLinkedVehicleUuids()).thenReturn(List.of(relevant));

    assertEquals(1, service.syncVehicles());

    verify(scWikiClient, never()).fetchExistenceCensus(any(), any(), anyInt());
    verify(shipTypeRepository).markScwikiDeletedExcept(eq(Set.of(relevant)), any());
  }

  @Test
  void failedVehicleFetchStillClosesItsVersionScope() {
    ScWikiClient.SyncRun scope = mock(ScWikiClient.SyncRun.class);
    when(scWikiClient.openSyncRun()).thenReturn(scope);
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenThrow(new IllegalStateException("source unavailable"));

    assertThrows(IllegalStateException.class, service::syncVehicles);

    verify(scope).close();
    verifyNoInteractions(shipTypeRepository);
  }

  @Test
  void aP4kKnownShipKeepsItsHullHealthAndClassName_andTheDisagreementIsReported() {
    UUID uuid = UUID.randomUUID();
    ShipType p4kKnown = new ShipType();
    p4kKnown.setId(UUID.randomUUID());
    p4kKnown.setName("Aegis Avenger Titan");
    p4kKnown.setExternalUuid(uuid);
    p4kKnown.setP4kUuid(UUID.randomUUID());
    p4kKnown.setClassName("AEGS_Avenger_Titan");
    p4kKnown.setHealth(11_900.0);
    ScWikiVehicleDto dto =
        ScWikiVehicleDto.builder()
            .uuid(uuid)
            .name("Avenger Titan")
            .className("AEGS_Avenger_Titan_Wiki")
            .health(12_500.5)
            .shieldHp(3_000.0)
            .build();
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(shipTypeRepository.findByExternalUuid(uuid)).thenReturn(Optional.of(p4kKnown));
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncVehicles();

    assertEquals(11_900.0, p4kKnown.getHealth());
    assertEquals("AEGS_Avenger_Titan", p4kKnown.getClassName());
    assertEquals(3_000.0, p4kKnown.getShieldHp());
    ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
    verify(syncReportService)
        .logScwikiEvent(
            any(),
            eq(SyncEventType.P4K_DISAGREEMENT),
            eq("ship_type"),
            eq(uuid),
            eq("Avenger Titan"),
            detail.capture());
    assertTrue(detail.getValue().contains("column=health;stored=11900.0;source=12500.5"));
    assertTrue(detail.getValue().contains("column=class_name;stored=AEGS_Avenger_Titan;"));
  }

  @Test
  void aP4kKnownShipWithoutHullHealthTakesTheWikiValue() {
    UUID uuid = UUID.randomUUID();
    ShipType p4kKnown = new ShipType();
    p4kKnown.setId(UUID.randomUUID());
    p4kKnown.setName("Aegis Javelin");
    p4kKnown.setExternalUuid(uuid);
    p4kKnown.setP4kUuid(UUID.randomUUID());
    ScWikiVehicleDto dto =
        ScWikiVehicleDto.builder().uuid(uuid).name("Javelin").health(7_852_400.0).build();
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(shipTypeRepository.findByExternalUuid(uuid)).thenReturn(Optional.of(p4kKnown));
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncVehicles();

    assertEquals(7_852_400.0, p4kKnown.getHealth());
    verify(syncReportService, never())
        .logScwikiEvent(any(), eq(SyncEventType.P4K_DISAGREEMENT), any(), any(), any(), any());
  }

  /** Supplies one relevant vehicle row that can be written without any prior local match. */
  private void singleVehicleCensus(UUID uuid) {
    ScWikiVehicleDto dto = ScWikiVehicleDto.builder().uuid(uuid).name("Relevant " + uuid).build();
    when(scWikiClient.fetchAllPagesResult(any(), any(), eq("vehicles"), any(), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto)));
    when(shipTypeRepository.save(any(ShipType.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }
}
