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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A bulk undo the executor refuses to queue ends {@code FAILED} at once, is counted as a failed run
 * and answers {@code 409}, while the client stays suspended (REQ-XCH-034, security review G5, I2).
 */
class ExchangeBulkUndoRejectionTest {

  private static final UUID REGISTRY_ID = UUID.fromString("7a1d2c3b-0000-0000-0000-0000000000c1");
  private static final UUID ADMIN = UUID.fromString("7a1d2c3b-0000-0000-0000-0000000000a1");

  private final ExchangeRegistryService registryService = mock(ExchangeRegistryService.class);
  private final ExchangeBulkUndoRunRepository runRepository =
      mock(ExchangeBulkUndoRunRepository.class);
  private final ExchangeJournalRepository journalRepository = mock(ExchangeJournalRepository.class);
  private final ExchangeBulkUndoRunner runner = mock(ExchangeBulkUndoRunner.class);
  private final ExchangeBulkUndoStep step = mock(ExchangeBulkUndoStep.class);
  private final AuthHelperService authHelperService = mock(AuthHelperService.class);
  private final Authentication admin = new TestingAuthenticationToken("admin", "n/a");

  private ExchangeBulkUndoService service;
  private ExchangeClient suspended;

  @BeforeEach
  void setUp() {
    service =
        new ExchangeBulkUndoService(
            registryService,
            mock(ExchangeClientRepository.class),
            mock(ExchangeInstallationRepository.class),
            journalRepository,
            runRepository,
            mock(ExchangeBulkUndoSkipRepository.class),
            runner,
            step,
            mock(ExchangeEntryLabels.class),
            mock(UserRepository.class),
            mock(AuditService.class),
            authHelperService,
            new ExchangeChangeRetentionProperties(true, Duration.ofDays(90)),
            mock(PlatformTransactionManager.class));
    ExchangeClient active = client(ExchangeClientStatus.ACTIVE);
    suspended = client(ExchangeClientStatus.SUSPENDED);
    when(registryService.getClient(REGISTRY_ID)).thenReturn(active);
    when(registryService.suspendClient(REGISTRY_ID, 3L)).thenReturn(suspended);
    when(runRepository.existsByExchangeClientIdAndStatus(
            REGISTRY_ID, ExchangeBulkUndoStatus.RUNNING))
        .thenReturn(false);
    when(runRepository.saveAndFlush(any(ExchangeBulkUndoRun.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(journalRepository.findUndoableMembers(anyString(), any(), isNull(), isNull()))
        .thenReturn(List.of());
    when(authHelperService.currentUserId()).thenReturn(Optional.of(ADMIN));
  }

  @Test
  void aRunTheExecutorRefusesEndsFailedAndAnswers409WithTheClientStillSuspended() {
    doThrow(new TaskRejectedException("queue full")).when(runner).run(any(UUID.class), eq(admin));

    assertThatThrownBy(() -> service.start(REGISTRY_ID, request(), admin))
        .isInstanceOfSatisfying(
            BusinessConflictException.class,
            e -> assertThat(e.getMessage()).isEqualTo("error.exchange.bulkUndo.queueFull"));

    verify(runner).run(any(UUID.class), eq(admin));
    verify(runner).rejected(any(UUID.class));
    verify(registryService).suspendClient(REGISTRY_ID, 3L);
    verify(registryService, never()).activateClient(any(), any());
  }

  @Test
  void anAcceptedRunIsNotEnded() {
    service.start(REGISTRY_ID, request(), admin);

    verify(runner).run(any(UUID.class), eq(admin));
    verify(runner, never()).rejected(any());
  }

  @Test
  void aRefusedRunIsEndedFailedAndCountedAsAFailedJobRun() {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    ExchangeBulkUndoRunner realRunner =
        new ExchangeBulkUndoRunner(
            runRepository,
            mock(ExchangeClientRepository.class),
            journalRepository,
            step,
            authHelperService,
            new TaskMetrics(meterRegistry));
    UUID runId = UUID.randomUUID();

    realRunner.rejected(runId);

    verify(step).finish(runId, ExchangeBulkUndoStatus.FAILED, false);
    Counter failures =
        meterRegistry
            .find(MetricNames.SCHEDULED_JOB_EXECUTIONS)
            .tag(MetricNames.TAG_JOB, ScheduledJob.EXCHANGE_BULK_UNDO.label())
            .tag(MetricNames.TAG_OUTCOME, MetricNames.OUTCOME_FAILURE)
            .counter();
    assertThat(failures).isNotNull();
    assertThat(failures.count()).isEqualTo(1.0d);
  }

  /**
   * Builds the client in one status.
   *
   * @param status the status
   * @return the client
   */
  private static ExchangeClient client(ExchangeClientStatus status) {
    ExchangeClient client = new ExchangeClient();
    client.setId(REGISTRY_ID);
    client.setVersion(status == ExchangeClientStatus.ACTIVE ? 3L : 4L);
    client.setClientId("versekit");
    client.setDisplayName("VerseKit");
    client.setStatus(status);
    return client;
  }

  /**
   * Builds a request for the last hour.
   *
   * @return the request
   */
  private static ExchangeBulkUndoRequest request() {
    return new ExchangeBulkUndoRequest(Instant.now().minusSeconds(3600), null, null);
  }
}
