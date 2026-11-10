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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiManufacturerSyncService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncChunkWriter;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncEventType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncReportService;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncRunProgress;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiManufacturerDto;
import de.greluc.krt.profit.basetool.backend.service.RecordingTransactionManager;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  /** The configured keys, relative to the record's prefix; {@link #rebuild()} binds them. */
  private final Map<String, Object> config = new HashMap<>();

  private ScWikiManufacturerSyncService service;

  @BeforeEach
  void setUp() {
    config.putAll(Map.of("manufacturer-sync-enabled", true));
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
        new ScWikiManufacturerSyncService(
            scWikiClient,
            properties,
            manufacturerRepository,
            syncReportService,
            new SyncChunkWriter(new RecordingTransactionManager(), new SyncRunProgress()));
  }

  @Test
  void syncManufacturers_isNoOp_whenFlagOff() {
    config.put("manufacturer-sync-enabled", false);
    rebuild();

    service.syncManufacturers();

    verifyNoInteractions(scWikiClient, manufacturerRepository, syncReportService);
  }

  @Test
  void syncManufacturers_abortsWithoutSweep_whenWikiReturnsEmpty() {
    when(scWikiClient.fetchAllPagesResult(eq(ENDPOINT), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of()));

    int written = service.syncManufacturers();

    verify(syncReportService, never()).beginRun();
    verify(manufacturerRepository, never()).markScwikiDeletedExcept(any(), any());
    verify(manufacturerRepository, never()).save(any());
    assertEquals(0, written, "an empty Wiki response must report zero reconciled rows");
    verify(syncReportService).recordSweep(false);
    verify(syncReportService, never()).recordIncomplete();
  }

  @Test
  void repeatedManufacturerPayloadsRefreshTheirSourceColumnsAgain() {
    UUID uuid = UUID.randomUUID();
    Manufacturer local = manufacturer("Aegis Dynamics", "AEGS");
    local.setScwikiUuid(uuid);
    when(scWikiClient.fetchAllPagesResult(eq(ENDPOINT), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto(uuid, "Aegis Dynamics", "AEGS"))));
    when(manufacturerRepository.findByScwikiUuid(uuid)).thenReturn(Optional.of(local));

    assertEquals(1, service.syncManufacturers());
    local.setScwikiCode("stale");
    assertEquals(1, service.syncManufacturers());

    assertEquals("AEGS", local.getScwikiCode());
    verify(manufacturerRepository, times(2)).save(local);
  }

  @Test
  void aP4kKnownManufacturer_matchesByItsP4kGuid_keepsItsNameAndCode_andReportsTheDifference() {
    UUID uuid = UUID.randomUUID();
    Manufacturer local = manufacturer("Aegis Dynamics", "AEG");
    local.setP4kUuid(uuid);
    when(scWikiClient.fetchAllPagesResult(eq(ENDPOINT), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto(uuid, "Aegis Dynamics Wiki", "AEGS"))));
    when(manufacturerRepository.findByP4kUuid(uuid)).thenReturn(Optional.of(local));

    assertEquals(1, service.syncManufacturers());

    assertEquals("Aegis Dynamics", local.getName());
    assertEquals("AEG", local.getAbbreviation());
    assertEquals(uuid, local.getScwikiUuid());
    assertEquals("AEGS", local.getScwikiCode());
    verify(syncReportService)
        .logP4kDisagreement(
            eq(SyncSourceSystem.SCWIKI),
            any(),
            eq("manufacturer"),
            eq(uuid),
            eq("name"),
            eq("Aegis Dynamics"),
            eq("Aegis Dynamics Wiki"));
    verify(syncReportService)
        .logP4kDisagreement(
            eq(SyncSourceSystem.SCWIKI),
            any(),
            eq("manufacturer"),
            eq(uuid),
            eq("abbreviation"),
            eq("AEG"),
            eq("AEGS"));
  }

  @Test
  void linksByName_firstTime_stampsScwikiColumns_logsLinked_runsSweep() {
    UUID uuid = UUID.randomUUID();
    Manufacturer local = manufacturer("Aegis Dynamics", "AEGS");
    when(scWikiClient.fetchAllPagesResult(eq(ENDPOINT), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto(uuid, "Aegis Dynamics", "AEGS"))));
    when(manufacturerRepository.findByScwikiUuid(uuid)).thenReturn(Optional.empty());
    when(manufacturerRepository.findByNameIgnoreCase("Aegis Dynamics"))
        .thenReturn(Optional.of(local));

    service.syncManufacturers();

    assertEquals(uuid, local.getScwikiUuid());
    assertEquals("AEGS", local.getScwikiCode());
    assertNotNull(local.getScwikiSyncedAt());
    assertNull(local.getScwikiDeletedAt());
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
    verify(manufacturerRepository).markScwikiDeletedExcept(any(), any());
  }

  @Test
  void linksByScwikiUuid_refresh_doesNotReLogLinked() {
    UUID uuid = UUID.randomUUID();
    Manufacturer local = manufacturer("Roberts Space Industries", "RSI");
    local.setScwikiUuid(uuid);
    when(scWikiClient.fetchAllPagesResult(eq(ENDPOINT), any(), any()))
        .thenReturn(
            ScWikiClient.FetchResult.of(List.of(dto(uuid, "Roberts Space Industries", "RSI"))));
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
    when(scWikiClient.fetchAllPagesResult(eq(ENDPOINT), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto(uuid, "Aegis", "AEGS"))));
    when(manufacturerRepository.findByScwikiUuid(uuid)).thenReturn(Optional.empty());
    when(manufacturerRepository.findByNameIgnoreCase("Aegis")).thenReturn(Optional.empty());
    when(manufacturerRepository.findByAbbreviationIgnoreCase("AEGS")).thenReturn(List.of(local));

    service.syncManufacturers();

    assertEquals(uuid, local.getScwikiUuid());
    assertEquals("Aegis Dynamics", local.getName());
    verify(manufacturerRepository).save(local);
  }

  @Test
  void conflict_whenCandidateAlreadyLinkedToDifferentUuid_logsMismatch_andSkips() {
    UUID incoming = UUID.randomUUID();
    UUID alreadyLinked = UUID.randomUUID();
    Manufacturer local = manufacturer("Drake Interplanetary", "DRAK");
    local.setScwikiUuid(alreadyLinked);
    when(scWikiClient.fetchAllPagesResult(eq(ENDPOINT), any(), any()))
        .thenReturn(
            ScWikiClient.FetchResult.of(List.of(dto(incoming, "Drake Interplanetary", "DRAK"))));
    when(manufacturerRepository.findByScwikiUuid(incoming)).thenReturn(Optional.empty());
    when(manufacturerRepository.findByNameIgnoreCase("Drake Interplanetary"))
        .thenReturn(Optional.of(local));

    service.syncManufacturers();

    assertEquals(alreadyLinked, local.getScwikiUuid());
    verify(manufacturerRepository, never()).save(any());
    verify(syncReportService)
        .logScwikiEvent(
            any(),
            eq(SyncEventType.MANUFACTURER_MISMATCH),
            eq("manufacturer"),
            eq(incoming),
            any(),
            any());
    verify(manufacturerRepository, never()).markScwikiDeletedExcept(any(), any());
  }

  @Test
  void unmatchedWikiManufacturer_isSkipped_noEvent_noSweep() {
    UUID uuid = UUID.randomUUID();
    when(scWikiClient.fetchAllPagesResult(eq(ENDPOINT), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto(uuid, "Some Indie Studio", "INDIE"))));
    when(manufacturerRepository.findByScwikiUuid(uuid)).thenReturn(Optional.empty());
    when(manufacturerRepository.findByNameIgnoreCase("Some Indie Studio"))
        .thenReturn(Optional.empty());
    when(manufacturerRepository.findByAbbreviationIgnoreCase("INDIE")).thenReturn(List.of());

    service.syncManufacturers();

    verify(manufacturerRepository, never()).save(any());
    verify(manufacturerRepository, never()).markScwikiDeletedExcept(any(), any());
    verify(syncReportService, never()).logScwikiEvent(any(), any(), any(), any(), any(), any());
    verify(syncReportService).recordSweep(false);
  }

  @Test
  void duplicateSourceNamesCannotLinkTheFirstManufacturerByName() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Manufacturer unclaimed = manufacturer("Aegis Dynamics", "AEGS");
    when(scWikiClient.fetchAllPagesResult(eq(ENDPOINT), any(), any()))
        .thenReturn(
            ScWikiClient.FetchResult.of(
                List.of(
                    dto(first, "Aegis Dynamics", "AEGS1"),
                    dto(second, " aegis dynamics ", "AEGS2"))));
    when(manufacturerRepository.findByNameIgnoreCase(any())).thenReturn(Optional.of(unclaimed));

    assertEquals(0, service.syncManufacturers());

    assertNull(unclaimed.getScwikiUuid());
    verify(manufacturerRepository, never()).save(any());
    verify(manufacturerRepository, never()).markScwikiDeletedExcept(any(), any());
    verify(syncReportService, times(2))
        .logScwikiEvent(
            any(),
            eq(SyncEventType.MULTI_MATCH_AMBIGUOUS),
            eq("manufacturer"),
            any(),
            any(),
            any());
  }

  @Test
  void duplicateSourceCodesCannotLinkTheFirstManufacturerByAbbreviation() {
    Manufacturer unclaimed = manufacturer("Aegis Dynamics", "AEGS");
    when(scWikiClient.fetchAllPagesResult(eq(ENDPOINT), any(), any()))
        .thenReturn(
            ScWikiClient.FetchResult.of(
                List.of(
                    dto(UUID.randomUUID(), "Unknown One", "AEGS"),
                    dto(UUID.randomUUID(), "Unknown Two", " aegs "))));
    when(manufacturerRepository.findByAbbreviationIgnoreCase(any())).thenReturn(List.of(unclaimed));

    assertEquals(0, service.syncManufacturers());

    assertNull(unclaimed.getScwikiUuid());
    verify(manufacturerRepository, never()).save(any());
    verify(manufacturerRepository, never()).markScwikiDeletedExcept(any(), any());
    verify(syncReportService, times(2))
        .logScwikiEvent(
            any(),
            eq(SyncEventType.MULTI_MATCH_AMBIGUOUS),
            eq("manufacturer"),
            any(),
            any(),
            any());
  }

  @Test
  void aSharedCodeHeldBySiblingOrCarriedByNoLocalRowLeavesTheCensusComplete() {
    UUID arcCorp = UUID.randomUUID();
    UUID casaba = UUID.randomUUID();
    UUID mirai = UUID.randomUUID();
    UUID musashi = UUID.randomUUID();
    Manufacturer linkedArcCorp = manufacturer("ArcCorp", "ARCC");
    linkedArcCorp.setScwikiUuid(arcCorp);
    when(scWikiClient.fetchAllPagesResult(eq(ENDPOINT), any(), any()))
        .thenReturn(
            ScWikiClient.FetchResult.of(
                List.of(
                    dto(arcCorp, "ArcCorp", "ARCC"),
                    dto(casaba, "Casaba Outlet", "ARCC"),
                    dto(mirai, "Mirai", "MIS"),
                    dto(musashi, "Musashi Industrial & Starflight Concern", "MIS"))));
    when(manufacturerRepository.findByScwikiUuid(arcCorp)).thenReturn(Optional.of(linkedArcCorp));
    when(manufacturerRepository.findByAbbreviationIgnoreCase("ARCC"))
        .thenReturn(List.of(linkedArcCorp));

    assertEquals(1, service.syncManufacturers());

    assertEquals(arcCorp, linkedArcCorp.getScwikiUuid());
    verify(manufacturerRepository).save(linkedArcCorp);
    verify(syncReportService, never())
        .logScwikiEvent(any(), eq(SyncEventType.MULTI_MATCH_AMBIGUOUS), any(), any(), any(), any());
    verify(syncReportService, never()).recordIncomplete();
    verify(manufacturerRepository).markScwikiDeletedExcept(any(), any());
    verify(syncReportService).recordSweep(true);
  }

  @Test
  void aSharedNameWhoseOnlyLocalRowIsLinkedToTheSiblingIsUnmatched() {
    UUID curelife = UUID.randomUUID();
    UUID cureLifeVariant = UUID.randomUUID();
    Manufacturer linked = manufacturer("Curelife", "CUR");
    linked.setP4kUuid(curelife);
    when(scWikiClient.fetchAllPagesResult(eq(ENDPOINT), any(), any()))
        .thenReturn(
            ScWikiClient.FetchResult.of(
                List.of(
                    dto(curelife, "Curelife", "CUR"), dto(cureLifeVariant, "CureLife", "CRLF"))));
    when(manufacturerRepository.findByP4kUuid(curelife)).thenReturn(Optional.of(linked));
    when(manufacturerRepository.findByNameIgnoreCase("CureLife")).thenReturn(Optional.of(linked));

    assertEquals(1, service.syncManufacturers());

    assertEquals(curelife, linked.getScwikiUuid());
    verify(syncReportService, never()).recordIncomplete();
    verify(syncReportService).recordSweep(true);
  }

  @Test
  void duplicatedLocalAbbreviationsDoNotChooseAFirstManufacturer() {
    UUID uuid = UUID.randomUUID();
    Manufacturer first = manufacturer("Aegis Dynamics", "AEGS");
    Manufacturer second = manufacturer("Aegis Defense", "AEGS");
    when(scWikiClient.fetchAllPagesResult(eq(ENDPOINT), any(), any()))
        .thenReturn(ScWikiClient.FetchResult.of(List.of(dto(uuid, "Aegis", "AEGS"))));
    when(manufacturerRepository.findByAbbreviationIgnoreCase("AEGS"))
        .thenReturn(List.of(first, second));

    assertEquals(0, service.syncManufacturers());

    assertNull(first.getScwikiUuid());
    assertNull(second.getScwikiUuid());
    verify(manufacturerRepository, never()).save(any());
    verify(manufacturerRepository, never()).markScwikiDeletedExcept(any(), any());
    verify(syncReportService)
        .logScwikiEvent(
            any(),
            eq(SyncEventType.MULTI_MATCH_AMBIGUOUS),
            eq("manufacturer"),
            eq(uuid),
            any(),
            any());
  }

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
