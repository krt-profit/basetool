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

import de.greluc.krt.profit.basetool.backend.config.AsyncConfig;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Works through an admin's bulk undo on the single-thread bulk undo executor (REQ-XCH-034): member
 * by member, each in its own transaction, as the admin who started it, and instrumented as the
 * {@code exchange_bulk_undo} job.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeBulkUndoRunner {

  private final ExchangeBulkUndoRunRepository runRepository;
  private final ExchangeClientRepository clientRepository;
  private final ExchangeJournalRepository journalRepository;
  private final ExchangeBulkUndoStep step;
  private final AuthHelperService authHelperService;
  private final TaskMetrics taskMetrics;

  /** Registers the job's outcome counters at zero, so the first failed run is visible. */
  @PostConstruct
  void registerMetrics() {
    taskMetrics.registerOutcomes(ScheduledJob.EXCHANGE_BULK_UNDO);
  }

  /**
   * Runs a committed bulk undo to its end; a failure is recorded on the run and in the job metrics,
   * never thrown.
   *
   * @param runId the run
   * @param admin the authentication of the admin who started it
   */
  @Async(AsyncConfig.EXCHANGE_BULK_UNDO_EXECUTOR)
  public void run(@NotNull UUID runId, @NotNull Authentication admin) {
    taskMetrics.recordCounting(
        ScheduledJob.EXCHANGE_BULK_UNDO,
        () -> {
          AtomicInteger processed = new AtomicInteger();
          authHelperService.runAs(admin, () -> processed.set(execute(runId)));
          return processed.get();
        });
  }

  /**
   * Ends a run the executor refused to queue as {@code FAILED}, audited like any other end, and
   * counts it as a failed {@code exchange_bulk_undo} run, which {@code ExchangeBulkUndoFailed}
   * alerts on.
   *
   * @param runId the refused run
   */
  public void rejected(@NotNull UUID runId) {
    taskMetrics.record(
        ScheduledJob.EXCHANGE_BULK_UNDO,
        () -> {
          step.finish(runId, ExchangeBulkUndoStatus.FAILED, false);
          throw new IllegalStateException(
              "Bulk undo run " + runId + " was refused because the queue is full");
        });
  }

  /**
   * Processes every member in the run's scope and ends the run.
   *
   * @param runId the run
   * @return how many members were processed
   * @throws IllegalStateException when a member could not be processed, after the run was ended
   */
  private int execute(@NotNull UUID runId) {
    Optional<ExchangeBulkUndoRun> found =
        runRepository.findById(runId).filter(r -> r.getStatus() == ExchangeBulkUndoStatus.RUNNING);
    if (found.isEmpty()) {
      return 0;
    }
    ExchangeBulkUndoRun run = found.get();
    int failed = 0;
    int processed = 0;
    try {
      ExchangeClient client =
          clientRepository
              .findWithCapabilitiesById(run.getExchangeClientId())
              .orElseThrow(() -> new IllegalStateException("The run's client is gone"));
      List<UUID> members =
          journalRepository.findUndoableMembers(
              run.getClientId(),
              run.getSince(),
              run.getInstallationKey(),
              run.getResource() == null ? null : run.getResource().name());
      for (UUID member : members) {
        try {
          step.processMember(run, client, member);
        } catch (RuntimeException e) {
          failed++;
          log.warn(
              "Bulk undo run {} could not process one member: {}",
              runId,
              e.getClass().getSimpleName());
          step.recordFailure(runId, member);
        }
        processed++;
      }
    } catch (RuntimeException e) {
      step.finish(runId, ExchangeBulkUndoStatus.FAILED, false);
      throw e;
    }
    step.finish(
        runId,
        failed == 0 ? ExchangeBulkUndoStatus.COMPLETED : ExchangeBulkUndoStatus.FAILED,
        false);
    if (failed > 0) {
      throw new IllegalStateException(
          "Bulk undo run " + runId + " could not process " + failed + " member(s)");
    }
    return processed;
  }
}
