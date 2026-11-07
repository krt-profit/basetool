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

import static de.greluc.krt.profit.basetool.backend.service.UexFetchResults.fetched;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerUexCompanyRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncChunkWriter;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncEventType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncReportService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncRunProgress;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.UexClient;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.UexVehicleService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexVehicleDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the §8.5 UUID-first resolution chain in the R2-hardened {@link UexVehicleService}. Critical
 * behaviours: name-fallback hits backfill {@code external_uuid} + {@code uex_vehicle_id} on the
 * matched row (R2's substitute for the planned V112 data migration); the 36 capability flags get
 * copied onto {@code ship_type}; UEX_ONLY rows are stamped on new inserts.
 */
@ExtendWith(MockitoExtension.class)
class UexVehicleServiceUuidMatchTest {

  @Mock private UexClient uexClient;
  @Mock private ShipTypeRepository shipTypeRepository;
  @Mock private ManufacturerRepository manufacturerRepository;
  @Mock private ManufacturerUexCompanyRepository manufacturerAliasRepository;
  @Mock private SyncReportService syncReportService;

  /** A real chunk writer, so the rows are actually written through its callbacks (BE-PERF-09). */
  @Spy
  private SyncChunkWriter chunkWriter =
      new SyncChunkWriter(new RecordingTransactionManager(), new SyncRunProgress());

  @InjectMocks private UexVehicleService service;

  private Manufacturer origin;
  private UUID externalUuid;

  @BeforeEach
  void setUp() {
    origin = new Manufacturer();
    origin.setId(UUID.randomUUID());
    origin.setName("Origin Jumpworks");
    externalUuid = UUID.fromString("6135a874-0000-0000-0000-000000000001");
  }

  @Test
  void matchByExternalUuid_shortCircuitsTheChain() {
    UexVehicleDto dto = vehicleDto(1, externalUuid.toString(), "100i");
    ShipType existing = new ShipType();
    existing.setId(UUID.randomUUID());
    existing.setName("100i");
    existing.setExternalUuid(externalUuid);
    when(uexClient.getVehicles()).thenReturn(fetched(List.of(dto)));
    when(shipTypeRepository.findByExternalUuid(externalUuid)).thenReturn(Optional.of(existing));
    when(manufacturerRepository.findByNameIgnoreCase("Origin Jumpworks"))
        .thenReturn(Optional.of(origin));
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));
    when(shipTypeRepository.markUexDeletedExcept(any(), any())).thenReturn(0);

    service.syncVehicles();

    verify(shipTypeRepository).findByExternalUuid(externalUuid);
    verify(shipTypeRepository).findByUexVehicleId(1);
    verify(shipTypeRepository, never()).findByNameIgnoreCase(any());
  }

  @Test
  void matchByUexVehicleId_whenUuidIsEmpty() {
    UexVehicleDto dto = vehicleDto(5, "", "Aurora Mk I CL");
    ShipType existing = new ShipType();
    existing.setId(UUID.randomUUID());
    existing.setName("Aurora Mk I CL");
    existing.setUexVehicleId(5);
    when(uexClient.getVehicles()).thenReturn(fetched(List.of(dto)));
    when(shipTypeRepository.findByUexVehicleId(5)).thenReturn(Optional.of(existing));
    when(manufacturerRepository.findByNameIgnoreCase("Origin Jumpworks"))
        .thenReturn(Optional.of(origin));
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));
    when(shipTypeRepository.markUexDeletedExcept(any(), any())).thenReturn(0);

    service.syncVehicles();

    verify(shipTypeRepository, never()).findByExternalUuid(any());
    verify(shipTypeRepository).findByUexVehicleId(5);
    verify(shipTypeRepository, never()).findByNameIgnoreCase(any());
  }

  @Test
  void nameFallback_backfillsExternalUuidAndUexVehicleId_onHit() {
    UexVehicleDto dto = vehicleDto(7, externalUuid.toString(), "100i");
    ShipType legacy = new ShipType();
    legacy.setId(UUID.randomUUID());
    legacy.setName("100i");
    when(uexClient.getVehicles()).thenReturn(fetched(List.of(dto)));
    when(shipTypeRepository.findByExternalUuid(externalUuid)).thenReturn(Optional.empty());
    when(shipTypeRepository.findByUexVehicleId(7)).thenReturn(Optional.empty());
    when(shipTypeRepository.findByNameIgnoreCase("100i")).thenReturn(Optional.of(legacy));
    when(manufacturerRepository.findByNameIgnoreCase("Origin Jumpworks"))
        .thenReturn(Optional.of(origin));
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));
    when(shipTypeRepository.markUexDeletedExcept(any(), any())).thenReturn(0);

    service.syncVehicles();

    ArgumentCaptor<ShipType> saved = ArgumentCaptor.forClass(ShipType.class);
    verify(shipTypeRepository).save(saved.capture());
    assertEquals(
        externalUuid,
        saved.getValue().getExternalUuid(),
        "name-fallback must backfill external_uuid");
    assertEquals(
        7, saved.getValue().getUexVehicleId(), "name-fallback must backfill uex_vehicle_id");
  }

  @Test
  void aliasedClassName_linksTheWikiRow_beforeTheNameFallback() {
    UexVehicleDto dto = vehicleDto(173, "", "San tok.Yai");
    ShipType wikiRow = new ShipType();
    wikiRow.setId(UUID.randomUUID());
    wikiRow.setName("San'tok.yai");
    wikiRow.setClassName("XNAA_SanTokYai");
    wikiRow.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
    when(uexClient.getVehicles()).thenReturn(fetched(List.of(dto)));
    when(shipTypeRepository.findByUexVehicleId(173)).thenReturn(Optional.empty());
    when(shipTypeRepository.findByUexAliasOfVehicleId(173)).thenReturn(List.of(wikiRow));
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));
    when(shipTypeRepository.markUexDeletedExcept(any(), any())).thenReturn(0);

    service.syncVehicles();

    ArgumentCaptor<ShipType> saved = ArgumentCaptor.forClass(ShipType.class);
    verify(shipTypeRepository).save(saved.capture());
    assertSame(wikiRow, saved.getValue());
    assertEquals(173, wikiRow.getUexVehicleId());
    assertEquals("San'tok.yai", wikiRow.getName(), "the matched row keeps its own name");
    assertEquals(GameItemSourceSystem.BOTH, wikiRow.getSourceSystems());
    verify(shipTypeRepository, never()).findByNameIgnoreCase(any());
  }

  @Test
  void aPartialFeedCannotReassignAnAbsentSourceIdThroughACuratedAlias() {
    UexVehicleDto dto = vehicleDto(173, "", "San tok.Yai");
    ShipType claimed = new ShipType();
    claimed.setId(UUID.randomUUID());
    claimed.setUexVehicleId(100);
    claimed.setName("Claimed source row");
    when(uexClient.getVehicles()).thenReturn(UexClient.FetchResult.partial(List.of(dto)));
    when(shipTypeRepository.findByUexAliasOfVehicleId(173)).thenReturn(List.of(claimed));

    service.syncVehicles();

    assertEquals(100, claimed.getUexVehicleId());
    assertEquals("Claimed source row", claimed.getName());
    verify(shipTypeRepository, never()).save(any());
    verify(shipTypeRepository, never()).markUexDeletedExcept(any(), any());
    verify(syncReportService).recordIncomplete();
  }

  @Test
  void aliasedClassName_onSeveralRows_fallsThroughToTheName() {
    UexVehicleDto dto = vehicleDto(174, "", "Twin");
    ShipType first = new ShipType();
    first.setClassName("DUP_Class");
    ShipType second = new ShipType();
    second.setClassName("DUP_Class");
    when(uexClient.getVehicles()).thenReturn(fetched(List.of(dto)));
    when(shipTypeRepository.findByUexVehicleId(174)).thenReturn(Optional.empty());
    when(shipTypeRepository.findByUexAliasOfVehicleId(174)).thenReturn(List.of(first, second));
    when(shipTypeRepository.findByNameIgnoreCase("Twin")).thenReturn(Optional.empty());
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));
    when(shipTypeRepository.markUexDeletedExcept(any(), any())).thenReturn(0);

    service.syncVehicles();

    ArgumentCaptor<ShipType> saved = ArgumentCaptor.forClass(ShipType.class);
    verify(shipTypeRepository).save(saved.capture());
    assertEquals(GameItemSourceSystem.UEX_ONLY, saved.getValue().getSourceSystems());
    assertNull(saved.getValue().getClassName());
  }

  @Test
  void createsNewRowAsUexOnly_whenNoMatchFound() {
    UexVehicleDto dto = vehicleDto(9, externalUuid.toString(), "Custom Ship");
    when(uexClient.getVehicles()).thenReturn(fetched(List.of(dto)));
    when(shipTypeRepository.findByExternalUuid(any())).thenReturn(Optional.empty());
    when(shipTypeRepository.findByUexVehicleId(any())).thenReturn(Optional.empty());
    when(shipTypeRepository.findByNameIgnoreCase(any())).thenReturn(Optional.empty());
    when(manufacturerRepository.findByNameIgnoreCase("Origin Jumpworks"))
        .thenReturn(Optional.of(origin));
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));
    when(shipTypeRepository.markUexDeletedExcept(any(), any())).thenReturn(0);

    service.syncVehicles();

    ArgumentCaptor<ShipType> saved = ArgumentCaptor.forClass(ShipType.class);
    verify(shipTypeRepository).save(saved.capture());
    assertEquals(GameItemSourceSystem.UEX_ONLY, saved.getValue().getSourceSystems());
    assertEquals("Custom Ship", saved.getValue().getName());
  }

  @Test
  void copies47Flags_ontoShipType_fromDto() {
    UexVehicleDto dto =
        new UexVehicleDto(
            12,
            externalUuid.toString(),
            "Hercules A2",
            "Crusader A2 Hercules Starlifter",
            "a2-hercules",
            "Crusader Industries",
            new BigDecimal("120"),
            "8",
            5_000_000.0,
            123.4,
            45.6,
            78.9,
            "L",
            12345.0,
            6789.0,
            "1,2",
            "store",
            "brochure",
            "hotsite",
            "photo",
            "video",
            0,
            0,
            1,
            1,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            1,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            1,
            0,
            0,
            0,
            0);
    when(uexClient.getVehicles()).thenReturn(fetched(List.of(dto)));
    when(shipTypeRepository.findByExternalUuid(externalUuid)).thenReturn(Optional.empty());
    when(shipTypeRepository.findByUexVehicleId(12)).thenReturn(Optional.empty());
    when(shipTypeRepository.findByNameIgnoreCase("Hercules A2")).thenReturn(Optional.empty());
    when(manufacturerRepository.findByNameIgnoreCase("Crusader Industries"))
        .thenReturn(Optional.empty());
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));
    when(shipTypeRepository.markUexDeletedExcept(any(), any())).thenReturn(0);

    service.syncVehicles();

    ArgumentCaptor<ShipType> saved = ArgumentCaptor.forClass(ShipType.class);
    verify(shipTypeRepository).save(saved.capture());
    ShipType persisted = saved.getValue();
    assertTrue(persisted.getIsBomber());
    assertTrue(persisted.getIsCargo());
    assertTrue(persisted.getIsMilitary());
    assertTrue(persisted.getIsSpaceship());
    assertFalse(persisted.getIsAddon());
    assertEquals(5_000_000.0, persisted.getMass());
    assertEquals(123.4, persisted.getWidth());
    assertEquals("L", persisted.getPadType());
    assertEquals(8, persisted.getCrewMin());
    assertEquals(8, persisted.getCrewMax());
    assertNull(persisted.getDescriptionEn());
    assertNull(persisted.getDescriptionDe());
    assertNull(persisted.getVehicleInventoryScu());
    assertNull(persisted.getMassTotal());
    assertNull(persisted.getOreCapacity());
    assertNull(persisted.getMaxMedicalTier());
    assertNull(persisted.getHealth());
    assertNull(persisted.getShieldHp());
    assertNull(persisted.getUrlWiki());
  }

  @Test
  void crewRange_isParsedFromTheCompactCrewString() {
    UexVehicleDto pair = vehicleDto(21, externalUuid.toString(), "Freelancer");
    when(uexClient.getVehicles()).thenReturn(fetched(List.of(pair)));
    when(shipTypeRepository.findByExternalUuid(externalUuid)).thenReturn(Optional.empty());
    when(shipTypeRepository.findByUexVehicleId(21)).thenReturn(Optional.empty());
    when(shipTypeRepository.findByNameIgnoreCase("Freelancer")).thenReturn(Optional.empty());
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));
    when(shipTypeRepository.markUexDeletedExcept(any(), any())).thenReturn(0);

    service.syncVehicles();

    ArgumentCaptor<ShipType> saved = ArgumentCaptor.forClass(ShipType.class);
    verify(shipTypeRepository).save(saved.capture());
    assertEquals(1, saved.getValue().getCrewMin());
    assertEquals(1, saved.getValue().getCrewMax());
  }

  @Test
  void orphanSweep_skipped_whenNoVehicleProcessed() {
    when(uexClient.getVehicles()).thenReturn(fetched(List.of()));

    service.syncVehicles();

    verify(shipTypeRepository, never()).markUexDeletedExcept(any(), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void duplicateGameUuidsNeverCollapseDistinctUexVehicles(boolean reverse) {
    UUID shared = UUID.fromString("087b2253-2fc7-4fae-9f9d-97f64b7c43db");
    ShipType prowler = new ShipType();
    prowler.setId(UUID.randomUUID());
    prowler.setName("Prowler");
    prowler.setUexVehicleId(149);
    prowler.setExternalUuid(shared);
    List<ShipType> rows = new ArrayList<>(List.of(prowler));
    when(shipTypeRepository.findByUexVehicleId(any()))
        .thenAnswer(inv ->
            rows.stream()
                .filter(row -> inv.getArgument(0).equals(row.getUexVehicleId()))
                .findFirst());
    when(shipTypeRepository.findByNameIgnoreCase(any()))
        .thenAnswer(inv ->
            rows.stream()
                .filter(row -> inv.<String>getArgument(0).equalsIgnoreCase(row.getName()))
                .findFirst());
    when(shipTypeRepository.save(any()))
        .thenAnswer(inv -> {
          ShipType row = inv.getArgument(0);
          if (row.getId() == null) {
            row.setId(UUID.randomUUID());
            rows.add(row);
          }
          return row;
        });
    UexVehicleDto base = vehicleDto(149, shared.toString(), "Prowler");
    UexVehicleDto utility = vehicleDto(262, shared.toString(), "Prowler Utility");
    when(uexClient.getVehicles())
        .thenReturn(fetched(reverse ? List.of(utility, base) : List.of(base, utility)));

    service.syncVehicles();

    assertEquals(2, rows.size());
    assertEquals(149, prowler.getUexVehicleId());
    assertEquals("Prowler", prowler.getName());
    ShipType sibling =
        rows.stream()
            .filter(row -> Integer.valueOf(262).equals(row.getUexVehicleId()))
            .findFirst()
            .orElseThrow();
    assertEquals("Prowler Utility", sibling.getName());
    assertNull(sibling.getExternalUuid());
    verify(shipTypeRepository, never()).findByExternalUuid(any());
  }

  @Test
  void incompleteVehicleFetchKeepsEveryLocalOrphanUntouched() {
    UexVehicleDto row = vehicleDto(149, "", "Prowler");
    when(uexClient.getVehicles()).thenReturn(UexClient.FetchResult.partial(List.of(row)));
    when(shipTypeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.syncVehicles();

    verify(shipTypeRepository, never()).markUexDeletedExcept(any(), any());
    verify(syncReportService).recordSweep(false);
  }

  @Test
  void aP4kKnownShipKeepsItsLedValuesAndTheDisagreementIsReported() {
    UexVehicleDto dto = vehicleDto(5, "", "Avenger Titan");
    Manufacturer aegis = new Manufacturer();
    aegis.setId(UUID.randomUUID());
    aegis.setName("Aegis Dynamics");
    ShipType p4kKnown = new ShipType();
    p4kKnown.setId(UUID.randomUUID());
    p4kKnown.setName("Aegis Avenger Titan");
    p4kKnown.setUexVehicleId(5);
    p4kKnown.setP4kUuid(UUID.randomUUID());
    p4kKnown.setManufacturer(aegis);
    p4kKnown.setCrewMax(2);
    p4kKnown.setMass(48_986.68);
    p4kKnown.setScu(new BigDecimal("8.000"));
    p4kKnown.setRole("Light Freight");
    when(uexClient.getVehicles()).thenReturn(fetched(List.of(dto)));
    when(shipTypeRepository.findByUexVehicleId(5)).thenReturn(Optional.of(p4kKnown));
    when(manufacturerRepository.findByNameIgnoreCase("Origin Jumpworks"))
        .thenReturn(Optional.of(origin));
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncVehicles();

    assertEquals(aegis, p4kKnown.getManufacturer());
    assertEquals(2, p4kKnown.getCrewMax());
    assertEquals(1, p4kKnown.getCrewMin());
    assertEquals(48_986.68, p4kKnown.getMass());
    assertEquals(0, new BigDecimal("8").compareTo(p4kKnown.getScu()));
    assertEquals("Light Freight", p4kKnown.getRole());
    assertEquals("Aegis Avenger Titan", p4kKnown.getName());
    ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
    verify(syncReportService)
        .logUexEvent(
            any(),
            eq(SyncEventType.P4K_DISAGREEMENT),
            eq("ship_type"),
            eq(p4kKnown.getP4kUuid()),
            eq("Avenger Titan"),
            detail.capture());
    assertTrue(detail.getValue().contains("column=manufacturer_id;stored=Aegis Dynamics"));
    assertTrue(detail.getValue().contains("column=crew_max;stored=2;source=1"));
    assertTrue(detail.getValue().contains("column=scu"));
    assertTrue(detail.getValue().contains("column=mass"));
  }

  @Test
  void aShipWithoutAP4kGuidTakesTheUexValues() {
    UexVehicleDto dto = vehicleDto(5, "", "Avenger Titan");
    ShipType uexOnly = new ShipType();
    uexOnly.setId(UUID.randomUUID());
    uexOnly.setName("Avenger Titan");
    uexOnly.setUexVehicleId(5);
    uexOnly.setCrewMax(2);
    when(uexClient.getVehicles()).thenReturn(fetched(List.of(dto)));
    when(shipTypeRepository.findByUexVehicleId(5)).thenReturn(Optional.of(uexOnly));
    when(manufacturerRepository.findByNameIgnoreCase("Origin Jumpworks"))
        .thenReturn(Optional.of(origin));
    when(shipTypeRepository.save(any(ShipType.class))).thenAnswer(inv -> inv.getArgument(0));

    service.syncVehicles();

    assertEquals(origin, uexOnly.getManufacturer());
    assertEquals(1, uexOnly.getCrewMax());
    verify(syncReportService, never())
        .logUexEvent(any(), eq(SyncEventType.P4K_DISAGREEMENT), any(), any(), any(), any());
  }

  private UexVehicleDto vehicleDto(int id, String uuid, String name) {
    return new UexVehicleDto(
        id,
        uuid,
        name,
        name + " Full",
        name.toLowerCase().replace(' ', '-'),
        "Origin Jumpworks",
        new BigDecimal("50"),
        "1",
        100_000.0,
        10.0,
        5.0,
        15.0,
        "S",
        100.0,
        50.0,
        "1",
        null,
        null,
        null,
        null,
        null,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0);
  }
}
