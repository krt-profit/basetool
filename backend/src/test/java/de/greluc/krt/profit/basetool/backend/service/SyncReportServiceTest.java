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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ExternalSyncReport;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.repository.ExternalSyncReportRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Unit tests for {@link SyncReportService}. */
@ExtendWith(MockitoExtension.class)
class SyncReportServiceTest {

  @Mock private ExternalSyncReportRepository repository;

  // A real registry (spied) so the per-source/event sync-event counter is genuinely recorded.
  @Spy private MeterRegistry meterRegistry = new SimpleMeterRegistry();

  @InjectMocks private SyncReportService service;

  @Test
  void beginRun_returnsAFreshNonNullId() {
    UUID a = service.beginRun();
    UUID b = service.beginRun();
    assertNotNull(a);
    assertNotEquals(a, b, "each run gets a distinct id");
  }

  @Test
  void logCommodityEvent_persistsScwikiCommodityRowWithGivenFields() {
    UUID runId = UUID.randomUUID();
    UUID externalUuid = UUID.randomUUID();

    service.logCommodityEvent(
        runId, SyncEventType.CREATED_WIKI_ONLY, externalUuid, "Bluemoon Fungus", "no UEX match");

    ArgumentCaptor<ExternalSyncReport> saved = ArgumentCaptor.forClass(ExternalSyncReport.class);
    verify(repository).save(saved.capture());
    ExternalSyncReport row = saved.getValue();
    assertEquals(runId, row.getRunId());
    assertEquals(SyncSourceSystem.SCWIKI, row.getSourceSystem());
    assertEquals(SyncEventType.CREATED_WIKI_ONLY, row.getEventType());
    assertEquals("commodity", row.getAggregate());
    assertEquals(externalUuid, row.getExternalUuid());
    assertEquals("Bluemoon Fungus", row.getExternalName());
    assertEquals("no UEX match", row.getDetail());
    assertNotNull(row.getRanAt());
    // The finding is counted once under the bounded source + event_type (REQ-OBS-011).
    assertEquals(
        1.0d,
        meterRegistry
            .get(MetricNames.SYNC_EVENTS)
            .tags(
                MetricNames.TAG_SOURCE,
                SyncSourceSystem.SCWIKI.name(),
                MetricNames.TAG_EVENT_TYPE,
                SyncEventType.CREATED_WIKI_ONLY.name())
            .counter()
            .count());
  }

  @Test
  void logUexEvent_persistsUexRowWithGivenFields() {
    UUID runId = UUID.randomUUID();

    service.logUexEvent(
        runId, SyncEventType.SYNC_RUN_SUMMARY, "game_item", null, null, "categories=1, upserted=1");

    ArgumentCaptor<ExternalSyncReport> saved = ArgumentCaptor.forClass(ExternalSyncReport.class);
    verify(repository).save(saved.capture());
    ExternalSyncReport row = saved.getValue();
    assertEquals(runId, row.getRunId());
    assertEquals(SyncSourceSystem.UEX, row.getSourceSystem());
    assertEquals(SyncEventType.SYNC_RUN_SUMMARY, row.getEventType());
    assertEquals("game_item", row.getAggregate());
    assertNull(row.getExternalUuid());
    assertNull(row.getExternalName());
    assertEquals("categories=1, upserted=1", row.getDetail());
    assertNotNull(row.getRanAt());
  }

  @Test
  void pruneRuns_deletesEverythingOutsideTheKeepSet() {
    List<UUID> kept = List.of(UUID.randomUUID(), UUID.randomUUID());
    when(repository.findRecentRunIds(eq(SyncSourceSystem.SCWIKI), any(Pageable.class)))
        .thenReturn(kept);
    when(repository.deleteBySourceAndRunIdNotIn(SyncSourceSystem.SCWIKI, kept)).thenReturn(7);

    service.pruneRuns(SyncSourceSystem.SCWIKI);

    verify(repository)
        .findRecentRunIds(
            eq(SyncSourceSystem.SCWIKI), eq(PageRequest.of(0, SyncReportService.RUNS_TO_KEEP)));
    verify(repository).deleteBySourceAndRunIdNotIn(SyncSourceSystem.SCWIKI, kept);
  }

  @Test
  void pruneRuns_skipsDelete_whenNoRunsExist() {
    when(repository.findRecentRunIds(any(), any(Pageable.class))).thenReturn(List.of());

    service.pruneRuns(SyncSourceSystem.SCWIKI);

    verify(repository, never()).deleteBySourceAndRunIdNotIn(any(), any());
  }

  @Test
  void deleteOlderThan_nullSource_usesCombinedDeleteAndReturnsCount() {
    when(repository.deleteByRanAtBefore(any())).thenReturn(3);

    int deleted = service.deleteOlderThan(null, 30);

    assertEquals(3, deleted);
    verify(repository).deleteByRanAtBefore(any());
    verify(repository, never()).deleteBySourceSystemAndRanAtBefore(any(), any());
  }

  @Test
  void deleteOlderThan_withSource_usesScopedDeleteAndReturnsCount() {
    when(repository.deleteBySourceSystemAndRanAtBefore(eq(SyncSourceSystem.UEX), any()))
        .thenReturn(5);

    int deleted = service.deleteOlderThan(SyncSourceSystem.UEX, 7);

    assertEquals(5, deleted);
    verify(repository).deleteBySourceSystemAndRanAtBefore(eq(SyncSourceSystem.UEX), any());
    verify(repository, never()).deleteByRanAtBefore(any());
  }

  @Test
  void deleteOlderThan_rejectsNonPositiveDaysWithoutTouchingRepository() {
    assertThrows(IllegalArgumentException.class, () -> service.deleteOlderThan(null, 0));

    verify(repository, never()).deleteByRanAtBefore(any());
    verify(repository, never()).deleteBySourceSystemAndRanAtBefore(any(), any());
  }

  @Test
  void findEvents_nullSource_usesCombinedQuery() {
    Page<ExternalSyncReport> empty = new PageImpl<>(List.of());
    when(repository.findAllByOrderByRanAtDesc(any(Pageable.class))).thenReturn(empty);

    service.findEvents(null, PageRequest.of(0, 50));

    verify(repository).findAllByOrderByRanAtDesc(any(Pageable.class));
    verify(repository, never()).findBySourceSystemOrderByRanAtDesc(any(), any());
  }

  @Test
  void findEvents_withSource_usesFilteredQuery() {
    Page<ExternalSyncReport> empty = new PageImpl<>(List.of());
    when(repository.findBySourceSystemOrderByRanAtDesc(eq(SyncSourceSystem.SCWIKI), any()))
        .thenReturn(empty);

    service.findEvents(SyncSourceSystem.SCWIKI, PageRequest.of(0, 50));

    verify(repository).findBySourceSystemOrderByRanAtDesc(eq(SyncSourceSystem.SCWIKI), any());
    verify(repository, never()).findAllByOrderByRanAtDesc(any());
  }
}
