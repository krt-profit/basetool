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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Decides what a currency wipe does to one aUEC order (REQ-PATCH-043…045), the same for its preview
 * and its run: the Bank attempts waiting since before the live time, the real shortage per holding
 * and the unit commitments opened before the live time.
 *
 * <p>A holding's shortage is the part of its booked amount that existed at the live time and is
 * still held: the lesser of its balance from the facts recorded before the live time and its
 * current balance less what still stays bound once the attempts are cancelled. Money added later
 * and money a later attempt still binds stay.
 */
@Component
@RequiredArgsConstructor
class JobOrderCurrencyWipePlanner {

  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyCommitmentRepository commitments;

  /**
   * Plans the wipe of one order from its current ledger.
   *
   * @param orderId the aUEC order
   * @param liveAt the live time of the patch
   * @return the plan
   */
  Plan plan(UUID orderId, Instant liveAt) {
    var now = ledger.read(orderId);
    var before = now.before(liveAt);
    List<JobOrderLinkedMoneyAttempt> cancelled =
        now.linkedAttempts().values().stream()
            .filter(attempt ->
                attempt.getState() == JobOrderLinkedMoneyState.WAITING
                    && attempt.getSubmittedAt().isBefore(liveAt))
            .sorted(Comparator.comparing(JobOrderLinkedMoneyAttempt::getId))
            .toList();
    Set<UUID> released =
        cancelled.stream()
            .filter(attempt -> !attempt.isActualPayment())
            .map(JobOrderLinkedMoneyAttempt::getId)
            .collect(Collectors.toSet());
    var bound =
        JobOrderMoneyAvailability.collect(
            now.linkedAttempts().values().stream()
                .filter(attempt -> !released.contains(attempt.getId()))
                .toList(),
            id -> now.holder(id).getId(),
            id -> now.recipientAliases().getOrDefault(id, id));
    List<Shortage> shortages = new ArrayList<>();
    sorted(now.balances())
        .forEach((holderId, balance) ->
            Shortage.of(
                    holderId,
                    null,
                    before.balance(holderId),
                    balance.subtract(bound.forHolder(holderId, null)))
                .ifPresent(shortages::add));
    sorted(now.accountBalances())
        .forEach((holdingId, balance) ->
            Shortage.of(
                    null,
                    holdingId,
                    before.accountBalance(holdingId),
                    balance.subtract(bound.forAccount(holdingId, null)))
                .ifPresent(shortages::add));
    List<JobOrderMoneyCommitment> ending =
        commitments.findByOrderIdAndEndedAtIsNullOrderById(orderId).stream()
            .filter(commitment -> commitment.getOpenedAt().isBefore(liveAt))
            .toList();
    return new Plan(now, cancelled, List.copyOf(shortages), ending);
  }

  /**
   * Orders the balances by position id, so the shortages book in a stable order.
   *
   * @param balances the balances by position
   * @return the same balances in id order
   */
  private static Map<UUID, BigDecimal> sorted(Map<UUID, BigDecimal> balances) {
    return new TreeMap<>(balances);
  }

  /**
   * What the wipe does to one order.
   *
   * @param ledger the order's current ledger, whose holders and positions the run updates
   * @param attempts the waiting attempts to cancel, in id order
   * @param shortages the shortages to book, holders first, each in id order
   * @param commitments the open commitments to end
   */
  record Plan(
      JobOrderMoneyLedger.Ledger ledger,
      List<JobOrderLinkedMoneyAttempt> attempts,
      List<Shortage> shortages,
      List<JobOrderMoneyCommitment> commitments) {

    /**
     * Tells whether the wipe leaves the order unchanged.
     *
     * @return whether nothing is cancelled, booked or ended
     */
    boolean isEmpty() {
      return attempts.isEmpty() && shortages.isEmpty() && commitments.isEmpty();
    }

    /**
     * Sums the shortages.
     *
     * @return the total lost, in whole aUEC
     */
    BigDecimal shortageTotal() {
      return shortages.stream().map(Shortage::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
  }

  /**
   * One holding's real shortage.
   *
   * @param holderId the canonical personal holder, or {@code null} for a Bank account position
   * @param accountHoldingId the Bank account position, or {@code null} for a personal holder
   * @param amount the lost amount, positive whole aUEC
   */
  record Shortage(@Nullable UUID holderId, @Nullable UUID accountHoldingId, BigDecimal amount) {

    /**
     * Derives a holding's shortage.
     *
     * @param holderId the canonical personal holder, or {@code null}
     * @param accountHoldingId the Bank account position, or {@code null}
     * @param atLiveTime the holding's balance as of the live time
     * @param spendable its current balance less what stays bound
     * @return the shortage, empty when nothing that existed at the live time is still held
     */
    static Optional<Shortage> of(
        @Nullable UUID holderId,
        @Nullable UUID accountHoldingId,
        BigDecimal atLiveTime,
        BigDecimal spendable) {
      BigDecimal lost = atLiveTime.min(spendable);
      return lost.signum() > 0
          ? Optional.of(new Shortage(holderId, accountHoldingId, lost))
          : Optional.empty();
    }
  }
}
