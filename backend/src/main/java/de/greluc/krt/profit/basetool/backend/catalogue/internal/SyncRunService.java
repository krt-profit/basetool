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

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists an independent start and finish around each catalogue application attempt. */
@Service
public class SyncRunService {

  private final ExternalSyncRunRepository repository;
  private final SyncRunProgress progress;
  private final MeterRegistry registry;
  private final AdminDashboardLiveSync liveSync;
  private final LiveSyncPublisher catalogueUpdates;
  private final TransactionTemplate transaction;
  private final ConcurrentHashMap<RunKey, AtomicLong> active = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<RunKey, AtomicLong> lastComplete = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<RunKey, AtomicLong> started = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<StatusKey, AtomicLong> statuses = new ConcurrentHashMap<>();
  private boolean startupRecovered;

  /**
   * Creates short independent transactions for recording the attempt lifecycle.
   *
   * @param repository durable attempt storage
   * @param progress calling-thread counters
   * @param registry bounded outcome and freshness metrics
   * @param liveSync notifies connected administration dashboards
   * @param catalogueUpdates notifies member catalogue views after committed source writes
   * @param transactionManager application transaction manager
   */
  public SyncRunService(
      ExternalSyncRunRepository repository,
      SyncRunProgress progress,
      MeterRegistry registry,
      AdminDashboardLiveSync liveSync,
      LiveSyncPublisher catalogueUpdates,
      @NotNull PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.progress = progress;
    this.registry = registry;
    this.liveSync = liveSync;
    this.catalogueUpdates = catalogueUpdates;
    transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Recovers interrupted attempts and metric state once before recording new attempts. */
  @EventListener(ApplicationStartedEvent.class)
  public synchronized void recoverInterruptedAttempts() {
    if (startupRecovered) {
      return;
    }
    transaction.executeWithoutResult(_ -> {
      int recovered =
          repository.failInterruptedAttempts(SyncRunStatus.RUNNING, SyncRunStatus.FAILED);
      if (recovered > 0) {
        liveSync.syncStatusChanged();
      }
      for (ExternalSyncRun run : repository.findLatestAttempts()) {
        RunKey key = new RunKey(run.getSourceSystem(), run.getAggregate());
        publishStatus(key, run.getStatus());
        active
            .computeIfAbsent(
                key, value -> gauge(value, CatalogueMetricNames.CATALOGUE_ATTEMPT_ACTIVE))
            .set(0);
        started
            .computeIfAbsent(
                key, value -> gauge(value, CatalogueMetricNames.CATALOGUE_ATTEMPT_STARTED))
            .set(run.getStartedAt().getEpochSecond());
      }
      for (ExternalSyncRun run : repository.findLatestCompleteAttempts()) {
        Instant completed = run.getFinishedAt();
        if (completed != null) {
          RunKey key = new RunKey(run.getSourceSystem(), run.getAggregate());
          lastComplete
              .computeIfAbsent(
                  key, value -> gauge(value, CatalogueMetricNames.CATALOGUE_ATTEMPT_LAST_COMPLETE))
              .set(completed.getEpochSecond());
        }
      }
    });
    startupRecovered = true;
  }

  /**
   * Records a source slice even when it produces no anomaly events or aborts before writing.
   *
   * @param source the upstream system
   * @param aggregate independently applied catalogue slice
   * @param gameVersion frozen source build, absent for unversioned feeds
   * @param task source fetch and application, returning its processed row count
   * @return the application outcome and the task's count
   */
  @NotNull
  public Result run(
      SyncSourceSystem source,
      SyncAggregate aggregate,
      @Nullable String gameVersion,
      @NotNull IntSupplier task) {
    recoverInterruptedAttempts();
    UUID id = UUID.randomUUID();
    final SyncRunProgress.Snapshot snapshot = progress.begin(id);
    RunKey key = new RunKey(source, aggregate);
    AtomicLong running =
        active.computeIfAbsent(
            key, value -> gauge(value, CatalogueMetricNames.CATALOGUE_ATTEMPT_ACTIVE));
    running.set(1);
    started
        .computeIfAbsent(key, value -> gauge(value, CatalogueMetricNames.CATALOGUE_ATTEMPT_STARTED))
        .set(Instant.now().getEpochSecond());
    publishStatus(key, SyncRunStatus.RUNNING);
    try {
      transaction.executeWithoutResult(_ -> {
        ExternalSyncRun run = new ExternalSyncRun();
        run.setId(id);
        run.setSourceSystem(source);
        run.setAggregate(aggregate);
        run.setGameVersion(gameVersion);
        run.setStartedAt(Instant.now());
        run.setStatus(SyncRunStatus.RUNNING);
        run.setSweepOutcome(SyncSweepOutcome.NOT_REQUIRED);
        repository.saveAndFlush(run);
        liveSync.syncStatusChanged();
      });
      int count;
      try {
        count = task.getAsInt();
      } catch (RuntimeException | Error failure) {
        if (snapshot.getFailed() == 0) {
          progress.failed(1);
        }
        finish(id, key, snapshot, SyncRunStatus.FAILED);
        throw failure;
      }
      SyncRunStatus status = snapshot.status();
      finish(id, key, snapshot, status);
      return new Result(count, status);
    } finally {
      running.set(0);
      progress.end();
    }
  }

  /**
   * Attaches the resolved source build to the already started attempt.
   *
   * @param gameVersion immutable upstream build selected for this source sweep
   */
  public void recordGameVersion(String gameVersion) {
    SyncRunProgress.Snapshot snapshot = progress.active();
    if (snapshot == null) {
      throw new IllegalStateException("A source build requires an active catalogue attempt");
    }
    transaction.executeWithoutResult(_ -> {
      ExternalSyncRun run = repository.findById(snapshot.getRunId()).orElseThrow();
      run.setGameVersion(gameVersion);
      repository.save(run);
    });
  }

  private void finish(
      UUID id, RunKey key, SyncRunProgress.Snapshot snapshot, SyncRunStatus status) {
    Instant finishedAt = Instant.now();
    transaction.executeWithoutResult(_ -> {
      ExternalSyncRun run = repository.findById(id).orElseThrow();
      run.setFinishedAt(finishedAt);
      run.setStatus(status);
      run.setFetched(snapshot.getFetched());
      run.setValid(snapshot.getValid());
      run.setApplied(snapshot.getApplied());
      run.setSkipped(snapshot.getSkipped());
      run.setFailed(snapshot.getFailed());
      run.setCensusComplete(snapshot.isFetchObserved() && snapshot.isCensusComplete());
      run.setSweepOutcome(snapshot.getSweepOutcome());
      repository.save(run);
      liveSync.syncStatusChanged();
      if (key.aggregate() == SyncAggregate.CATALOGUE && snapshot.getApplied() > 0) {
        catalogueUpdates.publishAfterCommit(
            LiveSyncTopic.global(LiveSyncTopicClass.CATALOGUE), List.of("catalogue"));
      }
    });
    registry
        .counter(
            CatalogueMetricNames.CATALOGUE_ATTEMPTS,
            CatalogueMetricNames.TAG_SOURCE,
            key.source().name(),
            CatalogueMetricNames.TAG_AGGREGATE,
            key.aggregate().name(),
            MetricNames.TAG_STATUS,
            status.name())
        .increment();
    publishStatus(key, status);
    if (status == SyncRunStatus.SUCCESS || status == SyncRunStatus.UNCHANGED) {
      lastComplete
          .computeIfAbsent(
              key, value -> gauge(value, CatalogueMetricNames.CATALOGUE_ATTEMPT_LAST_COMPLETE))
          .set(finishedAt.getEpochSecond());
    }
  }

  private void publishStatus(RunKey key, SyncRunStatus currentStatus) {
    for (SyncRunStatus status : SyncRunStatus.values()) {
      StatusKey statusKey = new StatusKey(key, status);
      AtomicLong value =
          statuses.computeIfAbsent(
              statusKey,
              entry -> {
                AtomicLong level = new AtomicLong();
                Gauge.builder(
                        CatalogueMetricNames.CATALOGUE_ATTEMPT_STATUS,
                        level,
                        AtomicLong::doubleValue)
                    .tag(CatalogueMetricNames.TAG_SOURCE, entry.run().source().name())
                    .tag(CatalogueMetricNames.TAG_AGGREGATE, entry.run().aggregate().name())
                    .tag(MetricNames.TAG_STATUS, entry.status().name())
                    .register(registry);
                return level;
              });
      value.set(status == currentStatus ? 1 : 0);
    }
  }

  private AtomicLong gauge(RunKey key, String name) {
    AtomicLong value = new AtomicLong();
    Gauge.builder(name, value, AtomicLong::doubleValue)
        .tag(CatalogueMetricNames.TAG_SOURCE, key.source().name())
        .tag(CatalogueMetricNames.TAG_AGGREGATE, key.aggregate().name())
        .register(registry);
    return value;
  }

  /**
   * Processed row count and whether the independently committed attempt was complete.
   *
   * @param count the source writer's reported count
   * @param status durable attempt outcome
   */
  public record Result(int count, SyncRunStatus status) {
    /**
     * Reports whether this attempt fully applied or reused its requested source snapshot.
     *
     * @return whether no known partial application remains
     */
    public boolean complete() {
      return status == SyncRunStatus.SUCCESS || status == SyncRunStatus.UNCHANGED;
    }
  }

  /** Bounded metric identity shared by one source and catalogue slice. */
  private record RunKey(SyncSourceSystem source, SyncAggregate aggregate) {}

  /** Bounded state-gauge identity for one catalogue slice. */
  private record StatusKey(RunKey run, SyncRunStatus status) {}
}
