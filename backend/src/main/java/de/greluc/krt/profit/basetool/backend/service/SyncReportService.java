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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collects sync findings into the append-only {@code external_sync_report} table (SC_WIKI_SYNC_
 * PLAN.md §8.8) and serves them back, paged, to the admin sync-report pages.
 *
 * <p>Shared by every sync service across the rollout. R3 is the first writer (the Wiki commodity
 * merge). A sync cycle calls {@link #beginRun()} once to obtain a {@code run_id}, then {@link
 * #logCommodityEvent} (or a future per-aggregate variant) for each finding, then {@link #pruneRuns}
 * at the end to enforce the §8.8 "keep the last 30 runs per source" retention.
 *
 * <p>The write methods carry no transaction annotation of their own: they are designed to be called
 * from within the calling sync's {@code @Transactional} boundary so the audit rows commit (or roll
 * back) atomically with the data changes they describe. The read methods open their own read-only
 * transaction for the controller.
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
  private void countSyncEvent(SyncSourceSystem source, SyncEventType eventType) {
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
   * Records one SC Wiki sync finding for an arbitrary aggregate. Stamps {@code source = SCWIKI} and
   * {@code ran_at = now}; the caller supplies the aggregate label ({@code "commodity"} / {@code
   * "game_item"} / {@code "ship_type"} / {@code "blueprint"}). Used by the R4 blueprint / item /
   * vehicle syncs; {@link #logCommodityEvent} delegates here for the R3 commodity merge.
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
   * Records one UEX-sync finding for an arbitrary aggregate. Stamps {@code source = UEX} and {@code
   * ran_at = now}; the caller supplies the aggregate label ({@code "game_item"} / {@code
   * "commodity"} / …). The UEX side was previously log-only — only the SC Wiki syncs wrote here —
   * so the {@code /admin/sync-reports/uex} tab stayed empty; this method is the first UEX writer.
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
   * Records one KRT-P4K-Reader-import finding for an arbitrary aggregate. Stamps {@code source =
   * P4K} and {@code ran_at = now}; the caller supplies the aggregate label ({@code "game_item"} /
   * {@code "ship_type"} / {@code "manufacturer"} / {@code "material"} / {@code "blueprint"}). The
   * P4K catalog import is the only writer — it emits {@link SyncEventType#LINKED_VIA_NAME} for a
   * canonical-UUID backfill reached through the name/slug fallback, {@link
   * SyncEventType#BACKFILL_AMBIGUOUS} when the existing canonical UUID disagrees with the P4K GUID
   * (kept, not overwritten), {@link SyncEventType#CREATED_FROM_P4K} for an opt-in seeded row, and
   * one {@link SyncEventType#SYNC_RUN_SUMMARY} per run.
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
   * Enforces the §8.8 retention: deletes every event of {@code source} whose run is older than the
   * newest {@link #RUNS_TO_KEEP}. No-op when the source has fewer than the cap (the keep set would
   * be the whole population). Skips the delete entirely on an empty keep set so the {@code NOT IN
   * ()} clause is never generated.
   *
   * <p>Annotated {@code @Transactional} so the {@code @Modifying} delete always runs inside a
   * transaction: it joins the caller's transaction when one is active and opens its own when a
   * caller (e.g. the SC Wiki item / blueprint sync, whose per-row writes are isolated in their own
   * {@code REQUIRES_NEW} transactions) invokes it without one.
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
   * Deletes every sync-report event older than {@code days} days, optionally scoped to one source.
   * Backs the admin "delete reports older than X days" maintenance action. The cutoff is {@code now
   * - days} computed at call time; rows with {@code ran_at} strictly before it are removed. When
   * {@code source} is {@code null} the purge spans both catalogues; otherwise it is confined to
   * that source.
   *
   * <p>Annotated {@code @Transactional} (read-write) so the {@code @Modifying} delete runs in its
   * own writable transaction even though the controller class is {@code @Transactional(readOnly =
   * true)}.
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
