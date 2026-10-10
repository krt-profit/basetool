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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exchange.api.events.ExchangeBulkUndoAppliedEvent;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactions of an admin's bulk undo (REQ-XCH-034): one per member, so a run's transactions
 * stay as small as one member's undo and a failing member rolls back only its own entries.
 */
@Component
@RequiredArgsConstructor
public class ExchangeBulkUndoStep {

  /** The reason recorded for a member the run could not process. */
  static final String FAILED = "FAILED";

  private final ExchangeUndoService undoService;
  private final ExchangeBulkUndoRunRepository runRepository;
  private final ExchangeBulkUndoSkipRepository skipRepository;
  private final AuditRecorder auditRecorder;
  private final ApplicationEventPublisher eventPublisher;
  private final Clock clock = Clock.systemUTC();

  /**
   * Undoes the run's scope for one member, keeps what it left alone, adds the outcome to the run
   * and announces a restore to the member after the commit.
   *
   * @param run the run
   * @param client the client
   * @param member the member
   */
  @Transactional
  public void processMember(
      @NotNull ExchangeBulkUndoRun run, @NotNull ExchangeClient client, @NotNull UUID member) {
    ExchangeUndoService.Outcome outcome =
        undoService.undoWithinRun(
            member,
            client,
            run.getSince(),
            run.getInstallationKey(),
            run.getResource(),
            run.getId());
    List<ExchangeBulkUndoSkip> skips =
        outcome.skipped().stream()
            .map(
                skipped ->
                    ExchangeBulkUndoSkip.builder()
                        .id(UUID.randomUUID())
                        .runId(run.getId())
                        .userId(member)
                        .journalEntryId(skipped.journalEntryId())
                        .resource(skipped.resource())
                        .reason(skipped.reason())
                        .build())
            .toList();
    skipRepository.saveAll(skips);
    runRepository.addProgress(run.getId(), outcome.restored(), skips.size(), 0);
    if (outcome.restored() > 0) {
      eventPublisher.publishEvent(
          new ExchangeBulkUndoAppliedEvent(
              member, run.getId(), client.getDisplayName(), outcome.restored()));
    }
  }

  /**
   * Records a member the run could not process, in a transaction of its own.
   *
   * @param runId the run
   * @param member the member
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(@NotNull UUID runId, @NotNull UUID member) {
    skipRepository.save(
        ExchangeBulkUndoSkip.builder()
            .id(UUID.randomUUID())
            .runId(runId)
            .userId(member)
            .reason(FAILED)
            .build());
    runRepository.addProgress(runId, 0, 0, 1);
  }

  /**
   * Ends a running run and audits the end with its totals; a run already ended stays as it is.
   *
   * @param runId the run
   * @param status {@code COMPLETED} or {@code FAILED}
   * @param interrupted whether the run ended because the backend restarted
   * @return whether this call ended the run
   */
  @Transactional
  public boolean finish(
      @NotNull UUID runId, @NotNull ExchangeBulkUndoStatus status, boolean interrupted) {
    if (runRepository.finish(runId, status, clock.instant()) == 0) {
      return false;
    }
    ExchangeBulkUndoRun run =
        Entities.require(runRepository.findById(runId), "Bulk undo run not found");
    auditRecorder.record(
        AuditEventType.EXCHANGE_BULK_UNDO_FINISHED,
        run.getExchangeClientId(),
        run.getClientId(),
        null,
        AuditDetails.of("run", run.getId())
            .with("status", status.name())
            .with("interrupted", interrupted)
            .with("members", run.getMembersDone())
            .with("failed", run.getMembersFailed())
            .with("restored", run.getRestored())
            .with("skipped", run.getSkipped()));
    return true;
  }
}
