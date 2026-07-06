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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiManufacturerDto;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.service.SyncReportService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ScWikiManufacturerSyncService} — the R6 manufacturer reconciliation
 * (SC_WIKI_SYNC_PLAN.md §11 R6 / §6.4). Covers the flag gate, the resolution chain (scwiki_uuid /
 * name / abbreviation), first-link vs refresh, the no-overwrite of UEX-canonical fields, the
 * already-linked-elsewhere conflict, and the gated orphan sweep.
 */
@ExtendWith(MockitoExtension.class)
class ScWikiManufacturerSyncServiceTest {

  private static final String ENDPOINT = "/api/manufacturers";

  @Mock private ScWikiClient scWikiClient;
  @Mock private ManufacturerRepository manufacturerRepository;
  @Mock private SyncReportService syncReportService;

  private ScWikiProperties properties;
  private ScWikiManufacturerSyncService service;

  @BeforeEach
  void setUp() {
    properties = new ScWikiProperties();
    properties.setManufacturerSyncEnabled(true);
    service =
        new ScWikiManufacturerSyncService(
            scWikiClient, properties, manufacturerRepository, syncReportService);
    lenient().when(syncReportService.beginRun()).thenReturn(UUID.randomUUID());
  }

  @Test
  void syncManufacturers_isNoOp_whenFlagOff() {
    properties.setManufacturerSyncEnabled(false);

    service.syncManufacturers();

    verifyNoInteractions(scWikiClient, manufacturerRepository, syncReportService);
  }

  @Test
  void syncManufacturers_abortsWithoutSweep_whenWikiReturnsEmpty() {
    when(scWikiClient.fetchAllPages(eq(ENDPOINT), any(), any())).thenReturn(List.of());

    int written = service.syncManufacturers();

    verify(syncReportService, never()).beginRun();
    verify(manufacturerRepository, never()).markScwikiDeletedExcept(any(), any());
    verify(manufacturerRepository, never()).save(any());
    // An empty Wiki response reconciles no rows → 0 items (#1041 item 2, SyncZeroItems).
    assertEquals(0, written, "an empty Wiki response must report zero reconciled rows");
  }

  @Test
  void linksByName_firstTime_stampsScwikiColumns_logsLinked_runsSweep() {
    UUID uuid = UUID.randomUUID();
    Manufacturer local = manufacturer("Aegis Dynamics", "AEGS");
    when(scWikiClient.fetchAllPages(eq(ENDPOINT), any(), any()))
        .thenReturn(List.of(dto(uuid, "Aegis Dynamics", "AEGS")));
    when(manufacturerRepository.findByScwikiUuid(uuid)).thenReturn(Optional.empty());
    when(manufacturerRepository.findByNameIgnoreCase("Aegis Dynamics"))
        .thenReturn(Optional.of(local));

    service.syncManufacturers();

    assertEquals(uuid, local.getScwikiUuid());
    assertEquals("AEGS", local.getScwikiCode());
    assertNotNull(local.getScwikiSyncedAt());
    assertNull(local.getScwikiDeletedAt());
    // UEX-canonical fields untouched.
    assertEquals("Aegis Dynamics", local.getName());
    assertEquals("AEGS", local.getAbbreviation());
    verify(manufacturerRepository).save(local);
    verify(syncReportService)
        .logScwikiEvent(
            any(),
            eq(SyncEventType.MANUFACTURER_LINKED),
            eq("manufacturer"),
            eq(uuid),
            any(),
            any());
    // One link → non-empty seen set → the orphan sweep runs.
    verify(manufacturerRepository).markScwikiDeletedExcept(any(), any());
  }

  @Test
  void linksByScwikiUuid_refresh_doesNotReLogLinked() {
    UUID uuid = UUID.randomUUID();
    Manufacturer local = manufacturer("Roberts Space Industries", "RSI");
    local.setScwikiUuid(uuid); // already linked on a prior run
    when(scWikiClient.fetchAllPages(eq(ENDPOINT), any(), any()))
        .thenReturn(List.of(dto(uuid, "Roberts Space Industries", "RSI")));
    when(manufacturerRepository.findByScwikiUuid(uuid)).thenReturn(Optional.of(local));

    service.syncManufacturers();

    assertNotNull(local.getScwikiSyncedAt());
    verify(manufacturerRepository).save(local);
    verify(syncReportService, never())
        .logScwikiEvent(any(), eq(SyncEventType.MANUFACTURER_LINKED), any(), any(), any(), any());
    verify(manufacturerRepository).markScwikiDeletedExcept(any(), any());
  }

  @Test
  void fallsBackToAbbreviationCode_whenNameMisses() {
    UUID uuid = UUID.randomUUID();
    Manufacturer local = manufacturer("Aegis Dynamics", "AEGS");
    when(scWikiClient.fetchAllPages(eq(ENDPOINT), any(), any()))
        .thenReturn(List.of(dto(uuid, "Aegis", "AEGS"))); // wiki name differs from UEX name
    when(manufacturerRepository.findByScwikiUuid(uuid)).thenReturn(Optional.empty());
    when(manufacturerRepository.findByNameIgnoreCase("Aegis")).thenReturn(Optional.empty());
    when(manufacturerRepository.findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc("AEGS"))
        .thenReturn(Optional.of(local));

    service.syncManufacturers();

    assertEquals(uuid, local.getScwikiUuid());
    assertEquals("Aegis Dynamics", local.getName()); // canonical name preserved
    verify(manufacturerRepository).save(local);
  }

  @Test
  void conflict_whenCandidateAlreadyLinkedToDifferentUuid_logsMismatch_andSkips() {
    UUID incoming = UUID.randomUUID();
    UUID alreadyLinked = UUID.randomUUID();
    Manufacturer local = manufacturer("Drake Interplanetary", "DRAK");
    local.setScwikiUuid(alreadyLinked);
    when(scWikiClient.fetchAllPages(eq(ENDPOINT), any(), any()))
        .thenReturn(List.of(dto(incoming, "Drake Interplanetary", "DRAK")));
    when(manufacturerRepository.findByScwikiUuid(incoming)).thenReturn(Optional.empty());
    when(manufacturerRepository.findByNameIgnoreCase("Drake Interplanetary"))
        .thenReturn(Optional.of(local));

    service.syncManufacturers();

    assertEquals(alreadyLinked, local.getScwikiUuid()); // link not hijacked
    verify(manufacturerRepository, never()).save(any());
    verify(syncReportService)
        .logScwikiEvent(
            any(),
            eq(SyncEventType.MANUFACTURER_MISMATCH),
            eq("manufacturer"),
            eq(incoming),
            any(),
            any());
    // Nothing reconciled → empty seen set → no sweep.
    verify(manufacturerRepository, never()).markScwikiDeletedExcept(any(), any());
  }

  @Test
  void unmatchedWikiManufacturer_isSkipped_noEvent_noSweep() {
    UUID uuid = UUID.randomUUID();
    when(scWikiClient.fetchAllPages(eq(ENDPOINT), any(), any()))
        .thenReturn(List.of(dto(uuid, "Some Indie Studio", "INDIE")));
    when(manufacturerRepository.findByScwikiUuid(uuid)).thenReturn(Optional.empty());
    when(manufacturerRepository.findByNameIgnoreCase("Some Indie Studio"))
        .thenReturn(Optional.empty());
    when(manufacturerRepository.findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc("INDIE"))
        .thenReturn(Optional.empty());

    service.syncManufacturers();

    verify(manufacturerRepository, never()).save(any());
    verify(manufacturerRepository, never()).markScwikiDeletedExcept(any(), any());
    verify(syncReportService, never()).logScwikiEvent(any(), any(), any(), any(), any(), any());
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /**
   * Builds a Wiki manufacturer payload.
   *
   * @param uuid Wiki manufacturer UUID
   * @param name display name
   * @param code short code
   * @return the payload
   */
  private static ScWikiManufacturerDto dto(UUID uuid, String name, String code) {
    return new ScWikiManufacturerDto(uuid, name, code);
  }

  /**
   * Builds a detached local manufacturer with a name and abbreviation (identity for {@code
   * verify(save)} / field assertions).
   *
   * @param name canonical UEX name
   * @param abbreviation canonical UEX abbreviation
   * @return the manufacturer
   */
  private static Manufacturer manufacturer(String name, String abbreviation) {
    Manufacturer m = new Manufacturer();
    m.setName(name);
    m.setAbbreviation(abbreviation);
    return m;
  }
}
