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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankEffects;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import jakarta.persistence.PersistenceException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Applies the exact Bank outcome to its prepared order attempt. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class JobOrderLinkedMoneyEffects implements JobOrderBankEffects {
  private final JobOrderLinkedMoneyAttemptRepository attempts;
  private final JobOrderRepository orders;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderEditor editor;
  private final JobOrderMoneyIdentityLock identity;
  private final JobOrderLinkedMoneyPosting posting;
  private final JobOrderLinkedMoneyReversals reversals;
  private final JobOrderMoneyEffects effects;
  private final JobOrderChangeEffects changes;
  private final JobOrderLinkedMoneyMetrics metrics;
  private final JobOrderLinkedMoneyQueueImpact queueImpact;
  private final JobOrderLinkedPartyIntegrity partyIntegrity;
  private final JobOrderMoneyAccountEffects accountEffects;

  @Override
  public void prepare(
      @NotNull UUID attemptId,
      @NotNull UUID purposeRef,
      @NotNull Action action,
      @NotNull UUID actorId) {
    UUID orderId =
        attempts
            .orderIdFor(attemptId, purposeRef)
            .orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
    if (action == Action.SUBMIT || action == Action.CANCEL_UNEXECUTED) {
      if (action == Action.CANCEL_UNEXECUTED) {
        JobOrderMoneyLifecycle.consumeCleanupPermit(attemptId);
      }
      var attempt = exact(attemptId, purposeRef);
      attempt.requireState(
          action == Action.SUBMIT
              ? JobOrderLinkedMoneyState.SUBMITTING
              : JobOrderLinkedMoneyState.WAITING);
      JobOrder order = orders.findById(orderId).orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
      if ((action == Action.SUBMIT && !actorId.equals(attempt.getActorId()))
          || (action == Action.CANCEL_UNEXECUTED
              && (attempt.isActualPayment() || order.getStatus().isActive()))) {
        throw JobOrderLinkedMoneyAttempt.conflict();
      }
      if (action == Action.SUBMIT) {
        requireBookableOrder(order, attempt);
        if (attempt.getRevision() > 1) {
          partyIntegrity.requireCurrent(order, attempt);
        }
      }
      retainPrepared(order, attempt, action, actorId);
      return;
    }
    Set<UUID> planned = new HashSet<>(attempts.identityAccounts(attemptId));
    identity.lockAffectedAccounts(actorId, planned);
    var impact = attempts.impactOf(attemptId).orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
    var order =
        editor.prepareLinkedBankEffect(
            orderId,
            current ->
                switch (action) {
                  case CONFIRM ->
                      queueImpact.posting(current, impact.getKind(), impact.getAmount());
                  case REVERSE ->
                      !impact.getActualPayment()
                          && queueImpact.reversal(current, impact.getKind(), impact.getAmount());
                  case SUBMIT, REJECT, CANCEL, CANCEL_UNEXECUTED -> false;
                });
    if (!planned.equals(new HashSet<>(attempts.identityAccounts(attemptId)))) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    var attempt = exact(attemptId, purposeRef);
    attempt.requireState(
        action == Action.REVERSE
            ? JobOrderLinkedMoneyState.POSTED
            : JobOrderLinkedMoneyState.WAITING);
    if (action == Action.CONFIRM) {
      requireBookableOrder(order, attempt);
      partyIntegrity.requireCurrent(order, attempt);
    }
    retainPrepared(order, attempt, action, actorId);
  }

  @Override
  public void apply(
      @NotNull UUID attemptId,
      @NotNull UUID purposeRef,
      @NotNull Decision decision,
      @NotNull UUID actorId) {
    try {
      applyPrepared(attemptId, purposeRef, decision, actorId);
    } catch (DataAccessException | PersistenceException failed) {
      metrics.technicalFailure();
      throw failed;
    }
  }

  private void applyPrepared(UUID attemptId, UUID purposeRef, Decision decision, UUID actorId) {
    var ready = prepared().byId.remove(attemptId);
    if (ready == null
        || !ready.actor().equals(actorId)
        || !purposeRef.equals(ready.attempt().getPurposeRef())
        || !matches(ready.action(), decision.kind())) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    JobOrder order = ready.order();
    var attempt = ready.attempt();
    boolean bound =
        attempt.getState() != JobOrderLinkedMoneyState.SUBMITTING && attempt.bindingActive();
    attempt.decide(decision, actorId);
    switch (decision.kind()) {
      case null -> throw JobOrderLinkedMoneyAttempt.conflict();
      case POSTED, AUTHORISED -> {
        changes.linkBankCause(order.getId(), decision.id());
        editor.recordOutcome(
            attempt.getAccountHoldingId() == null
                ? posting.post(order, attempt, actorId)
                : accountEffects.post(order, attempt));
      }
      case REVERSED -> {
        changes.linkBankCause(order.getId(), decision.id());
        if (!attempt.isActualPayment()) {
          if (attempt.getAccountHoldingId() == null) {
            reversals.reverse(order, attempt, actorId);
          } else {
            accountEffects.reverse(order, attempt, actorId);
          }
        }
        recordDecision(order, attempt, false);
      }
      case WAITING, REJECTED, CANCELLED -> {
        if (bound != attempt.bindingActive() && attempt.getSourceBinding().signum() > 0) {
          if (attempt.getAccountHoldingId() == null) {
            holders.findById(attempt.personalHolderId()).orElseThrow().recordChange();
          } else {
            accountEffects.updateBinding(attempt);
          }
        }
        recordDecision(order, attempt, decision.kind() == Outcome.WAITING);
      }
    }
  }

  private void recordDecision(JobOrder order, JobOrderLinkedMoneyAttempt attempt, boolean waiting) {
    effects.linkedAttemptChanged(order, waiting);
    editor.recordOutcome(
        JobOrderEdit.audited(
            order,
            AuditEventType.JOB_ORDER_MONEY_BANK_DECIDED,
            AuditDetails.of("operationId", attempt.getOperationId())
                .with("attemptId", attempt.getId())
                .with("state", attempt.getState())));
  }

  private static boolean matches(Action action, Outcome outcome) {
    return switch (action) {
      case SUBMIT ->
          outcome == Outcome.WAITING || outcome == Outcome.POSTED || outcome == Outcome.AUTHORISED;
      case CONFIRM -> outcome == Outcome.POSTED || outcome == Outcome.AUTHORISED;
      case REJECT -> outcome == Outcome.REJECTED;
      case CANCEL, CANCEL_UNEXECUTED -> outcome == Outcome.CANCELLED;
      case REVERSE -> outcome == Outcome.REVERSED;
    };
  }

  private static void retainPrepared(
      JobOrder order, JobOrderLinkedMoneyAttempt attempt, Action action, UUID actor) {
    if (prepared().byId.putIfAbsent(attempt.getId(), new Prepared(order, attempt, action, actor))
        != null) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
  }

  private static PreparedEffects prepared() {
    for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof PreparedEffects ready) {
        return ready;
      }
    }
    var ready = new PreparedEffects();
    TransactionSynchronizationManager.registerSynchronization(ready);
    return ready;
  }

  /** Associates a Bank callback with the order guard prepared for the exact decision. */
  private record Prepared(
      JobOrder order, JobOrderLinkedMoneyAttempt attempt, Action action, UUID actor) {}

  /** Tracks the prepared effects for the transaction. */
  private static final class PreparedEffects implements TransactionSynchronization {
    private final Map<UUID, Prepared> byId = new HashMap<>();
  }

  private JobOrderLinkedMoneyAttempt exact(UUID id, UUID purposeRef) {
    return attempts
        .findById(id)
        .filter(attempt -> purposeRef.equals(attempt.getPurposeRef()))
        .orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
  }

  private static void requireBookableOrder(JobOrder order, JobOrderLinkedMoneyAttempt attempt) {
    if (order.getStatus() == JobOrderStatus.REJECTED
        || (!order.getStatus().isActive() && !attempt.permitsTerminalPosting())) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
  }
}
