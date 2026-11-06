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
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Integration tests for the run summaries against real Postgres: a run counts once, by its newest
 * event, and only when that event lies inside the range.
 */
@SpringBootTest
class SyncRunSummaryIntegrationTest {

  private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");

  @Autowired private SyncReportService service;
  @Autowired private ExternalSyncReportRepository repository;
  @Autowired private ExternalSyncRunRepository attempts;
  @Autowired private SyncRunService runs;
  @Autowired private SyncRunProgress progress;
  @Autowired private PlatformTransactionManager transactionManager;

  @AfterEach
  void cleanUp() {
    repository.deleteAll();
    attempts.deleteAll();
  }

  @Test
  void cleanAndFailedAttempts_areVisibleWithoutAnomalyEvents() {
    runs.run(
        SyncSourceSystem.SCWIKI,
        SyncAggregate.CATALOGUE,
        "4.10.2-LIVE",
        () -> {
          progress.fetched(2, 2, true);
          progress.applied(2);
          return 2;
        });
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () ->
            runs.run(
                SyncSourceSystem.SCWIKI,
                SyncAggregate.CATALOGUE,
                "4.10.2-LIVE",
                () -> {
                  throw new IllegalStateException("source unavailable");
                }));

    SyncRunSummaryDto summary = service.runSummaries(7, Instant.now()).get(1);
    assertThat(summary.runs()).isEqualTo(2);
    assertThat(summary.lastStatus()).isEqualTo("FAILED");
    assertThat(summary.lastCompleteAt()).isNotNull();
    assertThat(summary.attempts()).hasSize(2);
    assertThat(summary.attempts().getFirst().finishedAt())
        .isAfterOrEqualTo(summary.attempts().getFirst().startedAt());
    assertThat(repository.count()).isZero();
  }

  @Test
  void interruptedAttempt_isFailedWithoutAnInventedFinishTime() {
    ExternalSyncRun interrupted = new ExternalSyncRun();
    interrupted.setId(UUID.randomUUID());
    interrupted.setSourceSystem(SyncSourceSystem.UEX);
    interrupted.setAggregate(SyncAggregate.CATALOGUE);
    interrupted.setStartedAt(Instant.now().minusSeconds(30));
    interrupted.setStatus(SyncRunStatus.RUNNING);
    interrupted.setSweepOutcome(SyncSweepOutcome.NOT_REQUIRED);
    attempts.saveAndFlush(interrupted);

    var restartedMetrics = new SimpleMeterRegistry();
    try {
      SyncRunService restarted =
          new SyncRunService(
              attempts,
              new SyncRunProgress(),
              restartedMetrics,
              mock(AdminDashboardLiveSync.class),
              mock(LiveSyncPublisher.class),
              mock(AuditRecorder.class),
              transactionManager);
      restarted.recoverInterruptedAttempts();
    } finally {
      restartedMetrics.close();
    }

    ExternalSyncRun stored = attempts.findById(interrupted.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(SyncRunStatus.FAILED);
    assertThat(stored.getFinishedAt()).isNull();
    assertThat(stored.getFailed()).isEqualTo(1);
    assertThat(attempts.findLatestAttempts())
        .extracting(ExternalSyncRun::getId)
        .containsExactly(interrupted.getId());
    assertThat(attempts.findLatestCompleteAttempts()).isEmpty();
  }

  @Test
  void runSummaries_countEachRunOnceByItsNewestEvent() {
    UUID straddling = UUID.randomUUID();
    event(SyncSourceSystem.UEX, straddling, NOW.minus(Duration.ofDays(8)));
    event(SyncSourceSystem.UEX, straddling, NOW.minus(Duration.ofDays(6)));
    event(SyncSourceSystem.UEX, straddling, NOW.minus(Duration.ofDays(6)));
    event(SyncSourceSystem.UEX, UUID.randomUUID(), NOW.minus(Duration.ofHours(1)));
    event(SyncSourceSystem.UEX, UUID.randomUUID(), NOW.minus(Duration.ofDays(9)));
    event(SyncSourceSystem.SCWIKI, UUID.randomUUID(), NOW.minus(Duration.ofDays(20)));

    List<SyncRunSummaryDto> week = service.runSummaries(7, NOW);
    List<SyncRunSummaryDto> day = service.runSummaries(1, NOW);

    assertThat(week)
        .containsExactly(
            new SyncRunSummaryDto("UEX", 2, NOW.minus(Duration.ofHours(1))),
            new SyncRunSummaryDto("SCWIKI", 0, NOW.minus(Duration.ofDays(20))),
            new SyncRunSummaryDto("P4K", 0, null));
    assertThat(day.getFirst().runs()).isEqualTo(1);
  }

  private void event(SyncSourceSystem source, UUID runId, Instant ranAt) {
    repository.save(
        ExternalSyncReport.builder()
            .runId(runId)
            .ranAt(ranAt)
            .sourceSystem(source)
            .eventType(SyncEventType.SYNC_RUN_SUMMARY)
            .aggregate("catalog")
            .detail("")
            .build());
  }
}
