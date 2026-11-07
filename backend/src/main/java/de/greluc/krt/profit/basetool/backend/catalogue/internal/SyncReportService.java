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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
 * last {@link #RUNS_TO_KEEP} runs per source and announce the finished run. Write methods join the
 * caller's transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncReportService {

  /** §8.8 retention: keep the last 30 runs per source. */
  public static final int RUNS_TO_KEEP = 30;

  /** The aggregate of a P4K run's summary event. */
  private static final String P4K_RUN_AGGREGATE = "catalog";

  private final ExternalSyncReportRepository repository;
  private final MeterRegistry meterRegistry;
  private final AdminDashboardLiveSync adminDashboardLiveSync;
  private final AuditRecorder auditRecorder;

  private final ExternalSyncRunRepository runRepository;
  private final SyncRunProgress progress;

  /**
   * Starts a new sync cycle and returns its {@code run_id}. Every event logged for this cycle
   * carries the same id so the admin UI can group them.
   *
   * @return a fresh run id
   */
  @NotNull
  public UUID beginRun() {
    SyncRunProgress.Snapshot active = progress.active();
    return active == null ? UUID.randomUUID() : active.getRunId();
  }

  /**
   * Records a source census in the active attempt.
   *
   * @param fetched decoded source rows
   * @param valid rows with usable identities
   * @param complete whether the requested scope was enumerated
   */
  public void recordFetch(long fetched, long valid, boolean complete) {
    progress.fetched(fetched, valid, complete);
  }

  /**
   * Adds independently committed rows to the active attempt.
   *
   * @param count committed rows
   */
  public void recordApplied(long count) {
    progress.applied(count);
  }

  /**
   * Adds rejected source rows to the active attempt.
   *
   * @param count deliberately skipped rows
   */
  public void recordSkipped(long count) {
    progress.skipped(count);
  }

  /**
   * Adds handled failures to the active attempt.
   *
   * @param count failed operations
   */
  public void recordFailed(long count) {
    progress.failed(count);
  }

  /** Marks the active attempt incomplete after a source identity or validation conflict. */
  public void recordIncomplete() {
    progress.invalidateCensus();
  }

  /** Accepts a verified complete union census without overriding identity rejection. */
  public void recordCompleteCensus() {
    progress.vouchForCensus();
  }

  /**
   * Records the active attempt's absence-based cleanup decision.
   *
   * @param performed whether cleanup ran
   */
  public void recordSweep(boolean performed) {
    progress.sweep(performed);
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
            CatalogueMetricNames.SYNC_EVENTS,
            CatalogueMetricNames.TAG_SOURCE,
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
      UUID runId,
      SyncEventType eventType,
      @Nullable UUID externalUuid,
      @Nullable String externalName,
      String detail) {
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
      @Nullable UUID externalUuid,
      @Nullable String externalName,
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
      @Nullable UUID externalUuid,
      @Nullable String externalName,
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
   * Records a UEX or SC Wiki value that differs from the value the P4K leads on a P4K-known row,
   * which keeps its value (REQ-P4K-020); an absent source value, or one equal to the P4K value
   * ignoring case, is no finding.
   *
   * @param source the source serving the value, {@code UEX} or {@code SCWIKI}
   * @param runId the current run's id (from {@link #beginRun()})
   * @param aggregate the aggregate the event concerns
   * @param p4kUuid the row's P4K GUID
   * @param column the column the values belong to
   * @param p4kValue the value the row keeps
   * @param sourceValue the value the source serves
   * @return whether an event was recorded
   */
  public boolean logP4kDisagreement(
      @NotNull SyncSourceSystem source,
      @NotNull UUID runId,
      @NotNull String aggregate,
      @NotNull UUID p4kUuid,
      @NotNull String column,
      @Nullable String p4kValue,
      @Nullable String sourceValue) {
    if (sourceValue == null
        || sourceValue.isBlank()
        || sourceValue.strip().equalsIgnoreCase(p4kValue)) {
      return false;
    }
    repository.save(
        ExternalSyncReport.builder()
            .runId(runId)
            .ranAt(Instant.now())
            .sourceSystem(source)
            .eventType(SyncEventType.P4K_DISAGREEMENT)
            .aggregate(aggregate)
            .externalUuid(p4kUuid)
            .externalName(p4kValue)
            .detail(P4kFields.disagreement(column, p4kValue, sourceValue.strip()))
            .build());
    countSyncEvent(source, SyncEventType.P4K_DISAGREEMENT);
    return true;
  }

  /**
   * Records the summary of one P4K APPLY: its counts per kind, as the run's {@code
   * SYNC_RUN_SUMMARY} event of the {@code catalog} aggregate.
   *
   * @param runId the run
   * @param summary the one-line counts per kind
   */
  public void logP4kRunSummary(@NotNull UUID runId, @NotNull String summary) {
    logP4kEvent(runId, SyncEventType.SYNC_RUN_SUMMARY, P4K_RUN_AGGREGATE, null, null, summary);
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
      @Nullable UUID externalUuid,
      @Nullable String externalName,
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
   * runs; a no-op when fewer runs exist. Joins the caller's transaction or opens one. Closes the
   * run: the open admin dashboards hear of it once the transaction commits.
   *
   * @param source the catalogue whose old runs should be pruned
   */
  @Transactional
  public void pruneRuns(SyncSourceSystem source) {
    adminDashboardLiveSync.syncStatusChanged();
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
  public int deleteOlderThan(@Nullable SyncSourceSystem source, int days) {
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
    auditRecorder.record(
        AuditEventType.CATALOGUE_SYNC_REPORTS_DELETED,
        null,
        null,
        null,
        AuditDetails.of("source", source == null ? "ALL" : source.name())
            .with("olderThanDays", days)
            .with("deleted", deleted));
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
  public Page<ExternalSyncReport> findEvents(@Nullable SyncSourceSystem source, Pageable pageable) {
    if (source == null) {
      return repository.findAllByOrderByRanAtDesc(pageable);
    }
    return repository.findBySourceSystemOrderByRanAtDesc(source, pageable);
  }

  /**
   * Counts, per source, the runs that finished at or after {@code now} minus {@code days}, next to
   * when each source last attempted and completely applied a sync. Legacy sources without durable
   * attempts retain their event-based summary.
   *
   * @param days the length of the range in days; at least 1
   * @param now the instant the range ends at
   * @return one summary per source system, in declaration order, with zero runs where none ran
   * @throws IllegalArgumentException if {@code days} is less than 1
   */
  @Transactional(readOnly = true)
  public @NotNull List<SyncRunSummaryDto> runSummaries(int days, @NotNull Instant now) {
    if (days < 1) {
      throw new IllegalArgumentException("days must be at least 1, was " + days);
    }
    Map<SyncSourceSystem, Long> runsInRange =
        repository.findRunFinishesSince(now.minus(Duration.ofDays(days))).stream()
            .collect(
                Collectors.groupingBy(
                    ExternalSyncReportRepository.RunFinish::getSourceSystem,
                    Collectors.counting()));
    Map<SyncSourceSystem, Instant> lastRun =
        repository.findLatestFinishPerSource().stream()
            .collect(
                Collectors.toMap(
                    ExternalSyncReportRepository.SourceFinish::getSourceSystem,
                    ExternalSyncReportRepository.SourceFinish::getFinishedAt));
    return Arrays.stream(SyncSourceSystem.values())
        .map(source ->
            summary(
                source,
                now.minus(Duration.ofDays(days)),
                runsInRange.getOrDefault(source, 0L),
                lastRun.get(source)))
        .toList();
  }

  private SyncRunSummaryDto summary(
      SyncSourceSystem source, Instant since, long legacyCount, @Nullable Instant legacyLast) {
    List<ExternalSyncRun> attempts =
        runRepository.findBySourceSystemOrderByStartedAtDesc(
            source, PageRequest.of(0, RUNS_TO_KEEP));
    if (attempts.isEmpty()) {
      return new SyncRunSummaryDto(source.name(), legacyCount, legacyLast);
    }
    ExternalSyncRun latest =
        attempts.stream()
            .filter(run -> run.getAggregate() == SyncAggregate.CATALOGUE)
            .findFirst()
            .orElse(attempts.getFirst());
    List<ExternalSyncRun> complete =
        runRepository.findBySourceSystemAndAggregateAndStatusInOrderByFinishedAtDesc(
            source,
            SyncAggregate.CATALOGUE,
            List.of(SyncRunStatus.SUCCESS, SyncRunStatus.UNCHANGED),
            PageRequest.of(0, 1));
    return new SyncRunSummaryDto(
        source.name(),
        runRepository.countBySourceSystemAndAggregateAndStartedAtGreaterThanEqual(
            source, SyncAggregate.CATALOGUE, since),
        latest.getStartedAt(),
        complete.isEmpty() ? null : complete.getFirst().getFinishedAt(),
        latest.getStatus().name(),
        attempts.stream().map(SyncRunDto::from).toList());
  }
}
