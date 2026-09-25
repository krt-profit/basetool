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

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ExternalSyncReport;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.repository.ExternalSyncReportRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records sync findings in the append-only {@code external_sync_report} table and serves them,
 * paged, to the admin sync-report pages.
 *
 * <p>A sync run calls {@link #beginRun()}, logs its findings, then {@link #pruneRuns} to keep the
 * last {@link #RUNS_TO_KEEP} runs per source. Write methods join the caller's transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncReportService {

  /** §8.8 retention: keep the last 30 runs per source. */
  public static final int RUNS_TO_KEEP = 30;

  private final ExternalSyncReportRepository repository;
  private final MeterRegistry meterRegistry;

  /**
   * Starts a new sync cycle and returns its {@code run_id}. Every event logged for this cycle
   * carries the same id so the admin UI can group them.
   *
   * @return a fresh run id
   */
  @NotNull
  public UUID beginRun() {
    return UUID.randomUUID();
  }

  /**
   * Increments {@code basetool_sync_events_total} for one recorded external-sync finding
   * (REQ-OBS-011). Both labels are bounded application enums — the source system and the event type
   * — never the external asset name, uuid or the free-form detail.
   *
   * @param source the sync source system
   * @param eventType the kind of finding
   */
  private void countSyncEvent(@NotNull SyncSourceSystem source, @NotNull SyncEventType eventType) {
    meterRegistry
        .counter(
            MetricNames.SYNC_EVENTS,
            MetricNames.TAG_SOURCE,
            source.name(),
            MetricNames.TAG_EVENT_TYPE,
            eventType.name())
        .increment();
  }

  /**
   * Records one Wiki-commodity-sync finding. Stamps {@code source = SCWIKI}, {@code aggregate =
   * "commodity"} and {@code ran_at = now}.
   *
   * @param runId the current run's id (from {@link #beginRun()})
   * @param eventType the kind of finding
   * @param externalUuid the Wiki commodity UUID the event concerns, or {@code null}
   * @param externalName the Wiki commodity display name, or {@code null}
   * @param detail free-form human-readable detail (e.g. the ambiguous candidate names)
   */
  public void logCommodityEvent(
      UUID runId, SyncEventType eventType, UUID externalUuid, String externalName, String detail) {
    logScwikiEvent(runId, eventType, "commodity", externalUuid, externalName, detail);
  }

  /**
   * Records one SC Wiki sync finding for the given aggregate, stamping {@code source = SCWIKI} and
   * {@code ran_at = now}.
   *
   * @param runId the current run's id (from {@link #beginRun()})
   * @param eventType the kind of finding
   * @param aggregate the aggregate the event concerns
   * @param externalUuid the external asset UUID the event concerns, or {@code null}
   * @param externalName the external display name, or {@code null}
   * @param detail free-form human-readable detail
   */
  public void logScwikiEvent(
      UUID runId,
      SyncEventType eventType,
      String aggregate,
      UUID externalUuid,
      String externalName,
      String detail) {
    repository.save(
        ExternalSyncReport.builder()
            .runId(runId)
            .ranAt(Instant.now())
            .sourceSystem(SyncSourceSystem.SCWIKI)
            .eventType(eventType)
            .aggregate(aggregate)
            .externalUuid(externalUuid)
            .externalName(externalName)
            .detail(detail)
            .build());
    countSyncEvent(SyncSourceSystem.SCWIKI, eventType);
  }

  /**
   * Records one UEX sync finding for the given aggregate, stamping {@code source = UEX} and {@code
   * ran_at = now}.
   *
   * @param runId the current run's id (from {@link #beginRun()})
   * @param eventType the kind of finding
   * @param aggregate the aggregate the event concerns
   * @param externalUuid the external asset UUID the event concerns, or {@code null}
   * @param externalName the external display name, or {@code null}
   * @param detail free-form human-readable detail (e.g. the per-run tally string)
   */
  public void logUexEvent(
      UUID runId,
      SyncEventType eventType,
      String aggregate,
      UUID externalUuid,
      String externalName,
      String detail) {
    repository.save(
        ExternalSyncReport.builder()
            .runId(runId)
            .ranAt(Instant.now())
            .sourceSystem(SyncSourceSystem.UEX)
            .eventType(eventType)
            .aggregate(aggregate)
            .externalUuid(externalUuid)
            .externalName(externalName)
            .detail(detail)
            .build());
    countSyncEvent(SyncSourceSystem.UEX, eventType);
  }

  /**
   * Records one P4K catalog-import finding for the given aggregate, stamping {@code source = P4K}
   * and {@code ran_at = now}.
   *
   * @param runId the current run's id (from {@link #beginRun()})
   * @param eventType the kind of finding
   * @param aggregate the aggregate the event concerns
   * @param externalUuid the external asset UUID the event concerns, or {@code null}
   * @param externalName the external display name, or {@code null}
   * @param detail free-form human-readable detail
   */
  public void logP4kEvent(
      UUID runId,
      SyncEventType eventType,
      String aggregate,
      UUID externalUuid,
      String externalName,
      String detail) {
    repository.save(
        ExternalSyncReport.builder()
            .runId(runId)
            .ranAt(Instant.now())
            .sourceSystem(SyncSourceSystem.P4K)
            .eventType(eventType)
            .aggregate(aggregate)
            .externalUuid(externalUuid)
            .externalName(externalName)
            .detail(detail)
            .build());
    countSyncEvent(SyncSourceSystem.P4K, eventType);
  }

  /**
   * Deletes every event of {@code source} whose run is older than the newest {@link #RUNS_TO_KEEP}
   * runs; a no-op when fewer runs exist. Joins the caller's transaction or opens one.
   *
   * @param source the catalogue whose old runs should be pruned
   */
  @Transactional
  public void pruneRuns(SyncSourceSystem source) {
    List<UUID> keptRunIds = repository.findRecentRunIds(source, PageRequest.of(0, RUNS_TO_KEEP));
    if (keptRunIds.isEmpty()) {
      return;
    }
    int deleted = repository.deleteBySourceAndRunIdNotIn(source, keptRunIds);
    if (deleted > 0) {
      log.info(
          "Pruned {} stale {} sync-report row(s) beyond the last {} runs.",
          deleted,
          source,
          RUNS_TO_KEEP);
    }
  }

  /**
   * Deletes sync-report events whose {@code ran_at} is more than {@code days} days in the past,
   * optionally for one source.
   *
   * @param source the catalogue to scope the purge to, or {@code null} for both
   * @param days the minimum age in days a report must exceed to be deleted; must be at least 1
   * @return number of rows deleted
   * @throws IllegalArgumentException if {@code days} is less than 1
   */
  @Transactional
  public int deleteOlderThan(SyncSourceSystem source, int days) {
    if (days < 1) {
      throw new IllegalArgumentException("days must be at least 1, was " + days);
    }
    Instant cutoff = Instant.now().minus(Duration.ofDays(days));
    int deleted =
        source == null
            ? repository.deleteByRanAtBefore(cutoff)
            : repository.deleteBySourceSystemAndRanAtBefore(source, cutoff);
    if (deleted > 0) {
      log.info(
          "Deleted {} sync-report row(s) older than {} day(s) for source {}.",
          deleted,
          days,
          source == null ? "ALL" : source);
    }
    return deleted;
  }

  /**
   * Returns one page of sync-report events, newest-first. When {@code source} is {@code null} the
   * page spans both catalogues (the combined admin view); otherwise it is filtered to that source.
   *
   * @param source the catalogue to filter to, or {@code null} for the combined view
   * @param pageable paging
   * @return one page of events newest-first
   */
  @Transactional(readOnly = true)
  public Page<ExternalSyncReport> findEvents(SyncSourceSystem source, Pageable pageable) {
    if (source == null) {
      return repository.findAllByOrderByRanAtDesc(pageable);
    }
    return repository.findBySourceSystemOrderByRanAtDesc(source, pageable);
  }
}
