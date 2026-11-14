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
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCurrencyWipe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a currency wipe over the aUEC orders (REQ-PATCH-043…045): it locks every order the live time
 * selects, cancels their waiting Bank attempts with their Bank requests, books the shortages and
 * ends the commitments the {@link JobOrderCurrencyWipePlanner} plans, and records one summary
 * event. Orders are locked in id order under the shared hierarchy guard, before any Bank request.
 */
@Service
@RequiredArgsConstructor
class JobOrderCurrencyWipeService implements JobOrderCurrencyWipe {

  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderCurrencyWipePlanner planner;
  private final JobOrderEditor editor;
  private final JobOrderMoneyCommitments commitments;
  private final JobOrderMoneyEffects effects;
  private final ObjectProvider<JobOrderBankPort> bank;
  private final AuditRecorder auditRecorder;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Effect preview(@NotNull Instant liveAt) {
    Tally tally = new Tally();
    operations.ordersWithMoneyBefore(liveAt).forEach(id -> tally.add(planner.plan(id, liveAt)));
    return tally.effect();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Effect apply(
      @NotNull Instant liveAt, @NotNull UUID patchChangeId, @NotNull String patchLabel) {
    Tally tally = new Tally();
    for (JobOrder order :
        editor.lockMoneyOrdersForPatchChange(operations.ordersWithMoneyBefore(liveAt))) {
      JobOrderCurrencyWipePlanner.Plan plan = planner.plan(order.getId(), liveAt);
      cancelAttempts(plan, patchChangeId);
      bookShortages(order.getId(), plan, patchChangeId);
      plan.commitments().forEach(commitments::endForCurrencyWipe);
      if (!plan.isEmpty()) {
        effects.currencyWiped(order, !plan.commitments().isEmpty());
      }
      tally.add(plan);
    }
    auditRecorder.record(
        AuditEventType.JOB_ORDER_MONEY_CURRENCY_WIPED,
        patchChangeId,
        patchLabel,
        null,
        tally.details().with("patchChange", patchChangeId));
    return tally.effect();
  }

  /**
   * Cancels the order's waiting attempts and their Bank requests; an attempt that releases its
   * binding moves its source's revision, as a Bank cancellation does.
   *
   * @param plan the order's plan
   * @param patchChangeId the patch change
   */
  private void cancelAttempts(
      @NotNull JobOrderCurrencyWipePlanner.Plan plan, @NotNull UUID patchChangeId) {
    if (plan.attempts().isEmpty()) {
      return;
    }
    JobOrderBankPort port = bank.getIfUnique();
    if (port == null) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    for (JobOrderLinkedMoneyAttempt attempt : plan.attempts()) {
      boolean bound = attempt.bindingActive();
      attempt.cancelForCurrencyWipe(patchChangeId);
      if (bound != attempt.bindingActive() && attempt.getSourceBinding().signum() > 0) {
        plan.ledger().recordChange(attempt.personalHolderId(), attempt.getAccountHoldingId());
      }
      port.cancelForCurrencyWipe(
          Objects.requireNonNull(attempt.getRequestId()),
          attempt.getId(),
          attempt.getPurposeRef(),
          patchChangeId);
    }
  }

  /**
   * Books the order's shortages, each its own operation that names the patch change.
   *
   * @param orderId the order
   * @param plan the order's plan
   * @param patchChangeId the patch change
   */
  private void bookShortages(
      @NotNull UUID orderId,
      @NotNull JobOrderCurrencyWipePlanner.Plan plan,
      @NotNull UUID patchChangeId) {
    for (JobOrderCurrencyWipePlanner.Shortage shortage : plan.shortages()) {
      plan.ledger().recordChange(shortage.holderId(), shortage.accountHoldingId());
      UUID holderId = shortage.holderId();
      operations.save(
          holderId != null
              ? JobOrderMoneyOperation.currencyWipeShortage(
                  orderId, holderId, shortage.amount(), patchChangeId)
              : JobOrderMoneyOperation.accountCurrencyWipeShortage(
                  orderId,
                  Objects.requireNonNull(shortage.accountHoldingId()),
                  shortage.amount(),
                  patchChangeId));
    }
  }

  /** Adds up the plans of every order the wipe touches. */
  private static final class Tally {

    private long orders;
    private long shortages;
    private BigDecimal shortageTotal = BigDecimal.ZERO;
    private long attempts;
    private long commitmentsEnded;

    /**
     * Adds one order's plan.
     *
     * @param plan the plan
     */
    void add(@NotNull JobOrderCurrencyWipePlanner.Plan plan) {
      if (!plan.shortages().isEmpty()) {
        orders++;
      }
      shortages += plan.shortages().size();
      shortageTotal = shortageTotal.add(plan.shortageTotal());
      attempts += plan.attempts().size();
      commitmentsEnded += plan.commitments().size();
    }

    /**
     * Returns the effect so far.
     *
     * @return the effect
     */
    @NotNull
    Effect effect() {
      return new Effect(shortages, shortageTotal, attempts, commitmentsEnded);
    }

    /**
     * Builds the summary event's details: counts only, never a name.
     *
     * @return the details
     */
    @NotNull
    AuditDetails details() {
      return AuditDetails.of("orders", orders)
          .with("shortages", shortages)
          .with("shortageTotal", shortageTotal.toPlainString())
          .with("attemptsCancelled", attempts)
          .with("commitmentsEnded", commitmentsEnded);
    }
  }
}
