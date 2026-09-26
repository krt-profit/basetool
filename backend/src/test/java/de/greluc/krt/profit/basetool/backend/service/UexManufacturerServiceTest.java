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
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.dto.uex.UexCompanyDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.ManufacturerUexCompany;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerUexCompanyRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for {@link UexManufacturerService} (REQ-DATA-004): duplicate UEX companies of one
 * brand merge onto one manufacturer, owned by the lowest company id, with the others registered as
 * aliases; matching is alias id, then name, then abbreviation.
 *
 * <p>The {@link #self} proxy is stubbed to return the service under test, so per-company failure
 * isolation is testable without transactions.
 */
@ExtendWith(MockitoExtension.class)
class UexManufacturerServiceTest {

  @Mock private UexClient uexClient;

  @Mock private ManufacturerRepository manufacturerRepository;

  @Mock private ManufacturerUexCompanyRepository aliasRepository;

  @Mock private ObjectProvider<UexManufacturerService> self;

  @InjectMocks private UexManufacturerService uexManufacturerService;

  @BeforeEach
  void wireSelfProxy() {
    lenient().when(self.getObject()).thenReturn(uexManufacturerService);
  }

  @Test
  void persistsTwoDistinctBrands_eachAsCanonical_withCrossRefColumnsAndAliases() {
    UexCompanyDto vehicleDto =
        UexCompanyDto.builder()
            .id(1)
            .name("Aegis Dynamics")
            .nickname("AEGS")
            .industry("Aerospace")
            .wiki("wiki-link")
            .isVehicleManufacturer(1)
            .build();
    UexCompanyDto itemDto =
        UexCompanyDto.builder()
            .id(2)
            .name("Casaba Outlet")
            .nickname("Casaba")
            .industry("Fashion")
            .isItemManufacturer(1)
            .isVehicleManufacturer(0)
            .build();

    when(uexClient.getCompanies()).thenReturn(fetched(List.of(vehicleDto, itemDto)));
    when(manufacturerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    uexManufacturerService.syncManufacturers();

    ArgumentCaptor<Manufacturer> captor = ArgumentCaptor.forClass(Manufacturer.class);
    verify(manufacturerRepository, times(2)).save(captor.capture());

    Manufacturer aegis = captor.getAllValues().get(0);
    assertEquals("Aegis Dynamics", aegis.getName());
    assertEquals("AEGS", aegis.getAbbreviation());
    assertEquals(1, aegis.getUexCompanyId());
    assertEquals("Aerospace", aegis.getIndustry());
    assertTrue(aegis.getIsVehicleManufacturer());
    assertFalse(aegis.getIsItemManufacturer());
    assertNotNull(aegis.getUexSyncedAt());

    Manufacturer casaba = captor.getAllValues().get(1);
    assertEquals("Casaba Outlet", casaba.getName());
    assertEquals(2, casaba.getUexCompanyId());
    assertTrue(casaba.getIsItemManufacturer());
    assertFalse(casaba.getIsVehicleManufacturer());

    verify(aliasRepository, times(2)).save(any());
  }

  @Test
  void matchByAliasId_shortCircuitsTheNameAndAbbreviationFallbacks() {
    UexCompanyDto dto =
        UexCompanyDto.builder()
            .id(42)
            .name("Aegis Dynamics")
            .nickname("AEGS-New")
            .industry("Aerospace-New")
            .isVehicleManufacturer(1)
            .build();
    Manufacturer existing = new Manufacturer();
    existing.setName("Aegis Dynamics");
    existing.setAbbreviation("AEGS-Old");
    existing.setUexCompanyId(42);

    when(uexClient.getCompanies()).thenReturn(fetched(List.of(dto)));
    when(aliasRepository.findManufacturerByUexCompanyId(42)).thenReturn(Optional.of(existing));
    when(manufacturerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    uexManufacturerService.syncManufacturers();

    verify(aliasRepository).findManufacturerByUexCompanyId(42);
    verify(manufacturerRepository, never()).findByNameIgnoreCase(any());
    verify(manufacturerRepository, never())
        .findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc(any());
    assertEquals("AEGS-New", existing.getAbbreviation());
    assertEquals("Aerospace-New", existing.getIndustry());
  }

  @Test
  void nameFallback_adoptsUnclaimedLegacyRow_andBackfillsCanonicalId_whenAliasMisses() {
    UexCompanyDto dto =
        UexCompanyDto.builder()
            .id(42)
            .name("Aegis Dynamics")
            .nickname("AEGS")
            .industry("Aerospace")
            .isVehicleManufacturer(1)
            .build();
    Manufacturer legacy = new Manufacturer();
    legacy.setName("Aegis Dynamics");
    legacy.setAbbreviation("AEGS");

    when(uexClient.getCompanies()).thenReturn(fetched(List.of(dto)));
    when(manufacturerRepository.findByNameIgnoreCase("Aegis Dynamics"))
        .thenReturn(Optional.of(legacy));
    when(manufacturerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    uexManufacturerService.syncManufacturers();

    verify(manufacturerRepository).save(legacy);
    assertEquals(
        42,
        legacy.getUexCompanyId(),
        "name-fallback hit on an unclaimed row backfills the canonical id");

    ArgumentCaptor<ManufacturerUexCompany> alias =
        ArgumentCaptor.forClass(ManufacturerUexCompany.class);
    verify(aliasRepository).save(alias.capture());
    assertEquals(42, alias.getValue().getUexCompanyId());
    assertSame(legacy, alias.getValue().getManufacturer());
  }

  @Test
  void abbreviationFallback_adoptsUnclaimedLegacyShortNamedRow_insteadOfInsertingDuplicate() {
    UexCompanyDto dto =
        UexCompanyDto.builder()
            .id(278)
            .name("Esperia Incorporation")
            .nickname("Esperia")
            .industry("Aerospace")
            .isItemManufacturer(1)
            .isVehicleManufacturer(1)
            .build();
    Manufacturer legacy = new Manufacturer();
    legacy.setName("Esperia");
    legacy.setAbbreviation("Esperia");

    when(uexClient.getCompanies()).thenReturn(fetched(List.of(dto)));
    when(manufacturerRepository.findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc("Esperia"))
        .thenReturn(Optional.of(legacy));
    when(manufacturerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    uexManufacturerService.syncManufacturers();

    ArgumentCaptor<Manufacturer> captor = ArgumentCaptor.forClass(Manufacturer.class);
    verify(manufacturerRepository, times(1)).save(captor.capture());
    Manufacturer saved = captor.getValue();
    assertSame(legacy, saved, "must adopt the abbreviation-matched row, not insert a new one");
    assertEquals(
        278, saved.getUexCompanyId(), "abbreviation-fallback hit must backfill the canonical id");
    assertEquals("Esperia", saved.getAbbreviation(), "the abbreviation label is preserved");
    assertEquals(
        "Esperia Incorporation", saved.getName(), "name is updated to the UEX-canonical full name");
  }

  @Test
  void twoCompaniesSharingAbbreviation_mergeOntoOneRow_secondBecomesAlias() {
    UexCompanyDto canonical =
        UexCompanyDto.builder()
            .id(278)
            .name("Esperia Incorporation")
            .nickname("Esperia")
            .isItemManufacturer(1)
            .isVehicleManufacturer(0)
            .build();
    UexCompanyDto duplicate =
        UexCompanyDto.builder()
            .id(279)
            .name("Esperia Defense Systems")
            .nickname("Esperia")
            .isItemManufacturer(0)
            .isVehicleManufacturer(1)
            .build();

    when(uexClient.getCompanies()).thenReturn(fetched(List.of(duplicate, canonical)));
    Manufacturer[] row = new Manufacturer[1];
    when(manufacturerRepository.save(any()))
        .thenAnswer(
            inv -> {
              row[0] = inv.getArgument(0);
              return row[0];
            });
    when(manufacturerRepository.findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc("Esperia"))
        .thenAnswer(inv -> Optional.ofNullable(row[0]));

    uexManufacturerService.syncManufacturers();

    assertNotNull(row[0]);
    assertEquals(278, row[0].getUexCompanyId(), "the lowest id stays canonical");
    assertEquals(
        "Esperia Incorporation", row[0].getName(), "the duplicate must not rename the row");
    assertTrue(row[0].getIsItemManufacturer(), "item flag comes from the canonical (278)");
    assertTrue(
        row[0].getIsVehicleManufacturer(), "vehicle flag is OR'd in from the duplicate (279)");

    ArgumentCaptor<ManufacturerUexCompany> aliasCaptor =
        ArgumentCaptor.forClass(ManufacturerUexCompany.class);
    verify(aliasRepository, times(2)).save(aliasCaptor.capture());
    Set<Integer> aliasedIds =
        aliasCaptor.getAllValues().stream()
            .map(ManufacturerUexCompany::getUexCompanyId)
            .collect(Collectors.toSet());
    assertEquals(Set.of(278, 279), aliasedIds);
  }

  @Test
  void oneCompanyUpsertFailing_doesNotAbortTheRestOfTheBatch() {
    UexCompanyDto poison =
        UexCompanyDto.builder().id(1).name("Esperia Incorporation").nickname("Esperia").build();
    UexCompanyDto healthy =
        UexCompanyDto.builder().id(2).name("Aegis Dynamics").nickname("AEGS").build();

    when(uexClient.getCompanies()).thenReturn(fetched(List.of(healthy, poison)));
    when(manufacturerRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate key"))
        .thenReturn(null);

    assertDoesNotThrow(() -> uexManufacturerService.syncManufacturers());

    ArgumentCaptor<Manufacturer> captor = ArgumentCaptor.forClass(Manufacturer.class);
    verify(manufacturerRepository, times(2)).save(captor.capture());
    assertEquals(
        "Aegis Dynamics",
        captor.getAllValues().get(1).getName(),
        "the healthy company after the failing one is still upserted");
  }

  @Test
  void emptyResponse_skipsWrites() {
    when(uexClient.getCompanies()).thenReturn(fetched(List.of()));

    uexManufacturerService.syncManufacturers();

    verify(manufacturerRepository, never()).save(any());
    verify(aliasRepository, never()).save(any());
  }
}
