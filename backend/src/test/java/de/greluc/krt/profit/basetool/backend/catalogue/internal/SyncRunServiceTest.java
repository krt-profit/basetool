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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.service.RecordingTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Verifies durable attempt lifecycle, nested counts and failure classification. */
@ExtendWith(MockitoExtension.class)
class SyncRunServiceTest {

  @Mock private ExternalSyncRunRepository repository;
  @Mock private AdminDashboardLiveSync liveSync;
  @Mock private LiveSyncPublisher catalogueUpdates;
  @Mock private AuditRecorder auditRecorder;
  private final SyncRunProgress progress = new SyncRunProgress();
  private final Map<UUID, ExternalSyncRun> stored = new LinkedHashMap<>();
  private SyncRunService service;

  @BeforeEach
  void setUp() {
    when(repository.saveAndFlush(any()))
        .thenAnswer(invocation -> {
          ExternalSyncRun run = invocation.getArgument(0);
          stored.put(run.getId(), run);
          return run;
        });
    when(repository.findById(any()))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
    service =
        new SyncRunService(
            repository,
            progress,
            new SimpleMeterRegistry(),
            liveSync,
            catalogueUpdates,
            auditRecorder,
            new RecordingTransactionManager());
  }

  @Test
  void finishedAttempt_recordsOneSystemSummaryWithItsCommittedOutcome() {
    service.run(
        SyncSourceSystem.UEX,
        SyncAggregate.GAME_ITEM,
        null,
        () -> {
          progress.fetched(5, 4, true);
          progress.applied(3);
          progress.failed(1);
          progress.sweep(false);
          return 3;
        });

    ExternalSyncRun run = stored.values().iterator().next();
    ArgumentCaptor<AuditDetails> details = ArgumentCaptor.forClass(AuditDetails.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.CATALOGUE_SYNC_RUN_FINISHED),
            eq(run.getId()),
            eq("UEX GAME_ITEM"),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString())
        .contains(
            "source=UEX",
            "aggregate=GAME_ITEM",
            "status=PARTIAL",
            "fetched=5",
            "applied=3",
            "failed=1",
            "sweep=SKIPPED");
  }

  @Test
  void thrownFailure_recordsAFailedSummary() {
    assertThatThrownBy(() ->
            service.run(
                SyncSourceSystem.SCWIKI,
                SyncAggregate.CATALOGUE,
                null,
                () -> {
                  throw new IllegalStateException("upstream unavailable");
                }))
        .isInstanceOf(IllegalStateException.class);

    ArgumentCaptor<AuditDetails> details = ArgumentCaptor.forClass(AuditDetails.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.CATALOGUE_SYNC_RUN_FINISHED),
            any(),
            eq("SCWIKI CATALOGUE"),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString()).contains("status=FAILED");
  }

  @Test
  void eventFreeAttempt_storesActualStartFinishAndCounters() {
    SyncRunService.Result result =
        service.run(
            SyncSourceSystem.SCWIKI,
            SyncAggregate.GAME_ITEM,
            "4.10.2-LIVE",
            () -> {
              assertThat(stored.values().iterator().next().getStatus())
                  .isEqualTo(SyncRunStatus.RUNNING);
              progress.fetched(3, 3, true);
              progress.applied(3);
              progress.sweep(true);
              return 3;
            });

    assertThat(result.complete()).isTrue();
    ExternalSyncRun run = stored.values().iterator().next();
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(run.getFinishedAt()).isAfterOrEqualTo(run.getStartedAt());
    assertThat(run.getFetched()).isEqualTo(3);
    assertThat(run.getApplied()).isEqualTo(3);
    assertThat(run.isCensusComplete()).isTrue();
    assertThat(run.getSweepOutcome()).isEqualTo(SyncSweepOutcome.PERFORMED);
    assertThat(progress.active()).isNull();
  }

  @Test
  void resolvedBuild_isAttachedToTheAttemptThatStartedBeforeResolution() {
    service.run(
        SyncSourceSystem.SCWIKI,
        SyncAggregate.CATALOGUE,
        null,
        () -> {
          ExternalSyncRun running = stored.values().iterator().next();
          assertThat(running.getStatus()).isEqualTo(SyncRunStatus.RUNNING);
          assertThat(running.getGameVersion()).isNull();
          service.recordGameVersion("4.10.2-LIVE");
          return 0;
        });

    assertThat(stored).hasSize(1);
    assertThat(stored.values().iterator().next().getGameVersion()).isEqualTo("4.10.2-LIVE");
  }

  @Test
  void caughtWriteFailure_storesPartial_andDoesNotClaimACompleteApply() {
    SyncRunService.Result result =
        service.run(
            SyncSourceSystem.UEX,
            SyncAggregate.ITEM_PRICE,
            null,
            () -> {
              progress.fetched(2, 2, true);
              progress.applied(1);
              progress.failed(1);
              progress.sweep(false);
              return 1;
            });

    assertThat(result.status()).isEqualTo(SyncRunStatus.PARTIAL);
    ExternalSyncRun run = stored.values().iterator().next();
    assertThat(run.getFailed()).isEqualTo(1);
    assertThat(run.getSweepOutcome()).isEqualTo(SyncSweepOutcome.SKIPPED);
  }

  @Test
  void thrownFailure_isRecordedBeforeItEscapes_andDoesNotLeakContext() {
    assertThatThrownBy(() ->
            service.run(
                SyncSourceSystem.UEX,
                SyncAggregate.SHIP_TYPE,
                null,
                () -> {
                  throw new IllegalStateException("upstream unavailable");
                }))
        .isInstanceOf(IllegalStateException.class);

    ExternalSyncRun run = stored.values().iterator().next();
    assertThat(run.getStatus()).isEqualTo(SyncRunStatus.FAILED);
    assertThat(run.getFailed()).isEqualTo(1);
    assertThat(run.getFinishedAt()).isNotNull();
    assertThat(progress.active()).isNull();
  }

  @Test
  void nestedAggregateAttempt_contributesOnlyCommittedCountersToItsSource() {
    service.run(
        SyncSourceSystem.UEX,
        SyncAggregate.CATALOGUE,
        null,
        () -> {
          service.run(
              SyncSourceSystem.UEX,
              SyncAggregate.GAME_ITEM,
              null,
              () -> {
                progress.fetched(4, 4, true);
                progress.applied(4);
                return 4;
              });
          progress.fetched(2, 2, true);
          progress.applied(2);
          return 6;
        });

    ExternalSyncRun source = stored.values().iterator().next();
    assertThat(source.getAggregate()).isEqualTo(SyncAggregate.CATALOGUE);
    assertThat(source.getFetched()).isEqualTo(6);
    assertThat(source.getApplied()).isEqualTo(6);
    assertThat(source.getStatus()).isEqualTo(SyncRunStatus.SUCCESS);
    assertThat(stored).hasSize(2);
  }

  @Test
  void verifiedResidualCensus_overridesRedundantWalkFailure_butNeverIdentityRejection() {
    service.run(
        SyncSourceSystem.SCWIKI,
        SyncAggregate.GAME_ITEM,
        null,
        () -> {
          progress.fetched(1, 1, false);
          progress.fetched(3, 3, true);
          progress.vouchForCensus();
          return 3;
        });
    assertThat(stored.values().iterator().next().getStatus()).isEqualTo(SyncRunStatus.SUCCESS);

    service.run(
        SyncSourceSystem.SCWIKI,
        SyncAggregate.GAME_ITEM,
        null,
        () -> {
          progress.fetched(3, 3, true);
          progress.invalidateCensus();
          progress.vouchForCensus();
          return 2;
        });
    assertThat(stored.values().stream().skip(1).findFirst().orElseThrow().getStatus())
        .isEqualTo(SyncRunStatus.PARTIAL);
  }

  @Test
  void partialSource_withCommittedRowsNotifiesMemberViews_butFetchOnlyFailureDoesNot() {
    service.run(
        SyncSourceSystem.UEX,
        SyncAggregate.CATALOGUE,
        null,
        () -> {
          progress.fetched(2, 2, true);
          progress.applied(1);
          progress.failed(1);
          return 1;
        });
    verify(catalogueUpdates).publishAfterCommit(any(), any());
    org.mockito.Mockito.clearInvocations(catalogueUpdates);

    assertThatThrownBy(() ->
            service.run(
                SyncSourceSystem.SCWIKI,
                SyncAggregate.CATALOGUE,
                null,
                () -> {
                  throw new IllegalStateException("Fetch failed before writes");
                }))
        .isInstanceOf(IllegalStateException.class);
    verify(catalogueUpdates, never()).publishAfterCommit(any(), any());
  }

  @Test
  void zeroWriteSource_doesNotTurnProcessedRowsIntoAppliedWritesOrPublish() {
    service.run(
        SyncSourceSystem.UEX,
        SyncAggregate.CATALOGUE,
        null,
        () -> {
          service.run(
              SyncSourceSystem.UEX,
              SyncAggregate.COMMODITY,
              null,
              () -> {
                progress.fetched(3, 3, true);
                progress.applied(0);
                return 3;
              });
          return 3;
        });

    assertThat(stored.values()).allSatisfy(run -> assertThat(run.getApplied()).isZero());
    verify(catalogueUpdates, never()).publishAfterCommit(any(), any());
  }

  @Test
  void skippedCleanup_isNotHiddenByLaterSuccessfulCleanup_orSourceRollup() {
    service.run(
        SyncSourceSystem.UEX,
        SyncAggregate.CATALOGUE,
        null,
        () -> {
          service.run(
              SyncSourceSystem.UEX,
              SyncAggregate.COMMODITY,
              null,
              () -> {
                progress.fetched(2, 2, true);
                progress.sweep(false);
                progress.sweep(true);
                return 0;
              });
          return 0;
        });

    assertThat(stored.values())
        .allSatisfy(run -> {
          assertThat(run.getStatus()).isEqualTo(SyncRunStatus.PARTIAL);
          assertThat(run.getSweepOutcome()).isEqualTo(SyncSweepOutcome.SKIPPED);
        });
  }

  @Test
  void startup_restoresFailureAndLastCompleteGauges_withoutReplayingCounters() {
    service.run(
        SyncSourceSystem.UEX,
        SyncAggregate.CATALOGUE,
        null,
        () -> {
          progress.fetched(1, 1, true);
          progress.applied(1);
          return 1;
        });
    ExternalSyncRun complete = stored.values().iterator().next();
    assertThatThrownBy(() ->
            service.run(
                SyncSourceSystem.UEX,
                SyncAggregate.CATALOGUE,
                null,
                () -> {
                  throw new IllegalStateException("source unavailable");
                }))
        .isInstanceOf(IllegalStateException.class);
    ExternalSyncRun failed = stored.values().stream().skip(1).findFirst().orElseThrow();
    when(repository.findLatestAttempts()).thenReturn(List.of(failed));
    when(repository.findLatestCompleteAttempts()).thenReturn(List.of(complete));
    SimpleMeterRegistry restoredMetrics = new SimpleMeterRegistry();
    SyncRunService restored =
        new SyncRunService(
            repository,
            progress,
            restoredMetrics,
            liveSync,
            catalogueUpdates,
            auditRecorder,
            new RecordingTransactionManager());

    restored.recoverInterruptedAttempts();

    assertThat(
            restoredMetrics
                .get(CatalogueMetricNames.CATALOGUE_ATTEMPT_STATUS)
                .tags("source", "UEX", "aggregate", "CATALOGUE", "status", "FAILED")
                .gauge()
                .value())
        .isEqualTo(1);
    assertThat(
            restoredMetrics
                .get(CatalogueMetricNames.CATALOGUE_ATTEMPT_LAST_COMPLETE)
                .tags("source", "UEX", "aggregate", "CATALOGUE")
                .gauge()
                .value())
        .isEqualTo(complete.getFinishedAt().getEpochSecond());
    assertThat(restoredMetrics.find(CatalogueMetricNames.CATALOGUE_ATTEMPTS).counter()).isNull();
  }
}
