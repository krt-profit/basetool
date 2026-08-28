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

import de.greluc.krt.profit.basetool.backend.dto.uex.UexVehicleDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerUexCompanyRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
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
    verify(shipTypeRepository, never()).findByUexVehicleId(any());
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
    legacy.setName("100i"); // no external_uuid, no uex_vehicle_id yet
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
            120,
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
            1, // is_bomber
            1, // is_cargo
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
            1, // is_military
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
            1, // is_spaceship
            0,
            0,
            0,
            0); // id_company
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
    // The crew range comes from UEX's compact `crew` string ("8"), not from crew_min / crew_max —
    // fields the payload does not carry (REQ-DATA-015).
    assertEquals(8, persisted.getCrewMin());
    assertEquals(8, persisted.getCrewMax());
    // And the fields UEX does not serve are LEFT ALONE rather than cleared: writing them nulled
    // eight columns outright and undid the SC-Wiki vehicle sync's description / inventory fill on
    // every run.
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
    // vehicleDto() carries crew "1" — a single number, which UEX means as "exactly one", so both
    // bounds are filled rather than leaving max null. UexValuesTest covers the "1,2" pair form.
    assertEquals(1, saved.getValue().getCrewMin());
    assertEquals(1, saved.getValue().getCrewMax());
  }

  @Test
  void orphanSweep_skipped_whenNoVehicleProcessed() {
    when(uexClient.getVehicles()).thenReturn(fetched(List.of()));

    service.syncVehicles();

    verify(shipTypeRepository, never()).markUexDeletedExcept(any(), any());
  }

  private UexVehicleDto vehicleDto(int id, String uuid, String name) {
    return new UexVehicleDto(
        id,
        uuid,
        name,
        name + " Full",
        name.toLowerCase().replace(' ', '-'),
        "Origin Jumpworks",
        50,
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
        0); // id_company
  }
}
