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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.livesync.api.AdminDashboardLiveSync;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.service.RecordingTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Verifies refresh-only startup safety and recovery ordering before newly recorded attempts. */
@ExtendWith(MockitoExtension.class)
class SyncRunServiceLifecycleTest {

  @Mock private ExternalSyncRunRepository repository;
  @Mock private AdminDashboardLiveSync dashboard;
  @Mock private LiveSyncPublisher catalogue;
  private final SyncRunProgress progress = new SyncRunProgress();
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final RecordingTransactionManager transactions = new RecordingTransactionManager();
  private final Map<UUID, ExternalSyncRun> stored = new ConcurrentHashMap<>();
  private AnnotationConfigApplicationContext context;
  private SyncRunService service;

  @BeforeEach
  void setUp() {
    service = new SyncRunService(repository, progress, metrics, dashboard, catalogue, transactions);
    context = new AnnotationConfigApplicationContext();
    context.registerBean(SyncRunService.class, () -> service);
    context.refresh();
  }

  @AfterEach
  void tearDown() {
    context.close();
    metrics.close();
  }

  @Test
  void refreshOnly_opensNoDatabaseTransaction_andActualStartRecoversExactlyOnce() {
    verifyNoInteractions(repository, dashboard, catalogue);
    assertThat(transactions.begun).isZero();

    publishStarted();
    publishStarted();

    verify(repository).failInterruptedAttempts(SyncRunStatus.RUNNING, SyncRunStatus.FAILED);
    verify(repository).findLatestAttempts();
    verify(repository).findLatestCompleteAttempts();
    assertThat(transactions.begun).isEqualTo(1);
    assertThat(transactions.committed).isEqualTo(1);
  }

  @Test
  void firstTickBeforeStarted_recoversBeforeSaving_andStartedDoesNotResetTheLiveAttempt() {
    stubAttemptWrites();

    SyncRunService.Result result =
        service.run(
            SyncSourceSystem.UEX,
            SyncAggregate.CATALOGUE,
            null,
            () -> {
              ExternalSyncRun running = stored.values().iterator().next();
              assertThat(running.getStatus()).isEqualTo(SyncRunStatus.RUNNING);
              publishStarted();
              assertThat(running.getStatus()).isEqualTo(SyncRunStatus.RUNNING);
              assertThat(
                      metrics
                          .get(CatalogueMetricNames.CATALOGUE_ATTEMPT_ACTIVE)
                          .tags("source", "UEX", "aggregate", "CATALOGUE")
                          .gauge()
                          .value())
                  .isEqualTo(1);
              progress.fetched(1, 1, true);
              progress.applied(1);
              return 1;
            });

    assertThat(result.status()).isEqualTo(SyncRunStatus.SUCCESS);
    var order = inOrder(repository);
    order.verify(repository).failInterruptedAttempts(SyncRunStatus.RUNNING, SyncRunStatus.FAILED);
    order.verify(repository).findLatestAttempts();
    order.verify(repository).findLatestCompleteAttempts();
    order.verify(repository).saveAndFlush(any());
    verify(repository, times(1))
        .failInterruptedAttempts(SyncRunStatus.RUNNING, SyncRunStatus.FAILED);
  }

  @Test
  void concurrentFirstTick_waitsUntilRecoveryAndMetricHydrationCommit() throws Exception {
    stubAttemptWrites();
    CountDownLatch recoveryEntered = new CountDownLatch(1);
    CountDownLatch releaseRecovery = new CountDownLatch(1);
    doAnswer(
            _ -> {
              recoveryEntered.countDown();
              assertThat(releaseRecovery.await(5, TimeUnit.SECONDS)).isTrue();
              return 0;
            })
        .when(repository)
        .failInterruptedAttempts(SyncRunStatus.RUNNING, SyncRunStatus.FAILED);
    when(repository.findLatestCompleteAttempts())
        .thenAnswer(_ -> {
          assertThat(stored).isEmpty();
          return List.of();
        });

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var recovery = executor.submit(this::publishStarted);
      assertThat(recoveryEntered.await(5, TimeUnit.SECONDS)).isTrue();
      var tick =
          executor.submit(
              () -> service.run(SyncSourceSystem.UEX, SyncAggregate.CATALOGUE, null, () -> 0));
      try {
        assertThatThrownBy(() -> tick.get(100, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        verify(repository, never()).saveAndFlush(any());
      } finally {
        releaseRecovery.countDown();
      }
      recovery.get(5, TimeUnit.SECONDS);
      assertThat(tick.get(5, TimeUnit.SECONDS).status()).isEqualTo(SyncRunStatus.SUCCESS);
    }
    assertThat(transactions.committed).isEqualTo(3);
    verify(repository, times(1))
        .failInterruptedAttempts(SyncRunStatus.RUNNING, SyncRunStatus.FAILED);
  }

  @Test
  void failedRecovery_propagatesAndRetriesBeforeOpeningAnAttempt() {
    stubAttemptWrites();
    IllegalStateException unavailable = new IllegalStateException("test database unavailable");
    when(repository.failInterruptedAttempts(SyncRunStatus.RUNNING, SyncRunStatus.FAILED))
        .thenThrow(unavailable)
        .thenReturn(0);

    assertThatThrownBy(this::publishStarted).isSameAs(unavailable);
    verify(repository, never()).saveAndFlush(any());
    assertThat(transactions.rolledBack).isEqualTo(1);

    SyncRunService.Result result =
        service.run(SyncSourceSystem.UEX, SyncAggregate.CATALOGUE, null, () -> 0);

    assertThat(result.status()).isEqualTo(SyncRunStatus.SUCCESS);
    verify(repository, times(2))
        .failInterruptedAttempts(SyncRunStatus.RUNNING, SyncRunStatus.FAILED);
    assertThat(stored).hasSize(1);
    assertThat(transactions.committed).isEqualTo(3);
  }

  /** Publishes the Boot boundary reached after a successful context refresh. */
  private void publishStarted() {
    context.publishEvent(
        new ApplicationStartedEvent(
            new SpringApplication(SyncRunServiceLifecycleTest.class),
            new String[0],
            context,
            Duration.ZERO));
  }

  /** Keeps committed attempt rows available for the real service's finish transaction. */
  private void stubAttemptWrites() {
    when(repository.saveAndFlush(any()))
        .thenAnswer(invocation -> {
          ExternalSyncRun run = invocation.getArgument(0);
          stored.put(run.getId(), run);
          return run;
        });
    when(repository.findById(any()))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
  }
}
