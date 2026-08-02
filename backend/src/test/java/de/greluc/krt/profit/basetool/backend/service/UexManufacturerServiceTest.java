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
 * Unit tests for {@link UexManufacturerService}.
 *
 * <p>The sync merges the duplicate UEX company records of one brand onto a single manufacturer row
 * (ADR-0023 / REQ-DATA-004): the canonical company — the lowest {@code uex_company_id}, processed
 * first because the feed is sorted ascending — owns the row's identity, while every other company
 * of the brand resolves to that row, registers its id in the {@code manufacturer_uex_company} alias
 * table and only ORs the manufacturer-surface flags. Match chain: alias-by-id → name →
 * abbreviation.
 *
 * <p>Each company is upserted through the {@link #self} proxy so its write runs in a dedicated
 * {@code REQUIRES_NEW} transaction (REQ-DATA-004). The proxy is stubbed to return the
 * service-under-test so the real upsert logic executes; the transaction boundary itself is a no-op
 * under plain Mockito, which is exactly why the per-company {@code catch} resilience is
 * unit-testable here.
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
    // self.getObject() must return the real instance so the per-company REQUIRES_NEW upsert runs
    // its actual logic; lenient() because the empty-response test returns before the loop.
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

    // Each company's id is mapped to its (own) manufacturer in the alias table.
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
    // alias id matches the row's canonical id, so this company owns the identity → full update.
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
    // legacy: no uexCompanyId yet (hand-seeded / pre-alias) — still unclaimed.

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
    // UEX returns the full company name, but the legacy vehicle-manufacturer row was seeded with
    // its short nickname as the name: local "Esperia" vs UEX name "Esperia Incorporation" /
    // nickname "Esperia". Both the id (alias) and name lookups miss, so the abbreviation fallback
    // adopts the legacy row rather than stranding it and inserting a parallel one.
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
    // legacy: unclaimed (no uexCompanyId yet).

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

  // covers REQ-DATA-004 / ADR-0023 — two distinct UEX companies that share an abbreviation are the
  // SAME brand and MERGE onto one row: the lowest id stays canonical, the other becomes an alias
  // and
  // only widens the manufacturer-surface flags without hijacking the canonical identity.
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

    // Deliberately unsorted: the service must sort ascending so the lowest id (278) is canonical.
    when(uexClient.getCompanies()).thenReturn(fetched(List.of(duplicate, canonical)));
    Manufacturer[] row = new Manufacturer[1];
    when(manufacturerRepository.save(any()))
        .thenAnswer(
            inv -> {
              row[0] = inv.getArgument(0);
              return row[0];
            });
    // The abbreviation fallback returns whatever the canonical company persisted earlier this run.
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

    // Exactly two alias rows — both company ids point at the one surviving manufacturer.
    ArgumentCaptor<ManufacturerUexCompany> aliasCaptor =
        ArgumentCaptor.forClass(ManufacturerUexCompany.class);
    verify(aliasRepository, times(2)).save(aliasCaptor.capture());
    Set<Integer> aliasedIds =
        aliasCaptor.getAllValues().stream()
            .map(ManufacturerUexCompany::getUexCompanyId)
            .collect(Collectors.toSet());
    assertEquals(Set.of(278, 279), aliasedIds);
  }

  // covers REQ-DATA-004 — a single failing company must not poison the batch: its REQUIRES_NEW
  // transaction rolls back alone and the loop continues, so the remaining companies still commit.
  @Test
  void oneCompanyUpsertFailing_doesNotAbortTheRestOfTheBatch() {
    // Poison has the lower id, so it is processed first; the healthy company follows.
    UexCompanyDto poison =
        UexCompanyDto.builder().id(1).name("Esperia Incorporation").nickname("Esperia").build();
    UexCompanyDto healthy =
        UexCompanyDto.builder().id(2).name("Aegis Dynamics").nickname("AEGS").build();

    when(uexClient.getCompanies()).thenReturn(fetched(List.of(healthy, poison)));
    // First save simulates a residual constraint violation (as the per-company tx would surface);
    // the second company must still be saved.
    when(manufacturerRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate key"))
        .thenReturn(null);

    // The sweep swallows the per-company failure instead of propagating it.
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
