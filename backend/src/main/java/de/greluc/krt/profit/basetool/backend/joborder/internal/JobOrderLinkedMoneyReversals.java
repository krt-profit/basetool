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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Reverses the effective order facts of a pure Bank posting as a linked correction. */
@Component
@RequiredArgsConstructor
class JobOrderLinkedMoneyReversals {
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyCorrectionEntryRepository corrections;
  private final JobOrderMoneyCommitmentCorrection credits;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderService lifecycle;
  private final JobOrderMoneyEffects effects;

  void reverse(JobOrder order, JobOrderLinkedMoneyAttempt attempt, UUID actor) {
    UUID orderId = order.getId();
    var current = ledger.read(orderId);
    var original =
        operations
            .findByOrderIdAndOperationId(orderId, attempt.getOperationId())
            .orElseThrow(JobOrderLinkedMoneyAttempt::conflict);
    UUID correctionId = UUID.randomUUID();
    var entry =
        new JobOrderMoneyCorrectionEntry(
            orderId,
            correctionId,
            original.getOperationId(),
            current.effective(original),
            new JobOrderMoneyAmounts(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    var entries = List.of(entry);
    var revised = current.revised(entries);
    if (!revised.nonnegativeAvailable()) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    credits.attachLinkedReversalCredit(order, original, entry);
    var source = current.holder(original.personalHolderId());
    final var marker =
        operations.saveAndFlush(
            JobOrderMoneyOperation.correction(
                orderId, correctionId, Objects.requireNonNull(source.getId()), actor, null));
    corrections.save(entry);
    credits.apply(order, correctionId, entries);
    source.recordChange();
    var parties = new ArrayList<JobOrderMoneyEffects.CorrectionParty>();
    if (source.getUserId() != null) {
      parties.add(
          new JobOrderMoneyEffects.CorrectionParty(source.getId(), null, source.getUserId()));
    }
    UUID destinationId = original.getDestinationHolderId();
    if (destinationId != null) {
      var destination = current.holder(destinationId);
      destination.recordChange();
      if (destination.getUserId() != null) {
        parties.add(
            new JobOrderMoneyEffects.CorrectionParty(
                destination.getId(), null, destination.getUserId()));
      }
    }
    UUID recipientId = original.getRecipientId();
    UUID requester = recipientId == null ? null : recipients.accountFor(orderId, recipientId);
    if (requester != null) {
      parties.add(new JobOrderMoneyEffects.CorrectionParty(null, recipientId, requester));
    }
    boolean reopened =
        order.getStatus() == JobOrderStatus.COMPLETED
            && targets.amountFor(orderId).orElseThrow().compareTo(revised.handedOver()) > 0;
    boolean completes =
        original.getKind() == JobOrderMoneyKind.REQUESTER_RETURN
            && order.getStatus().isActive()
            && targets.amountFor(orderId).orElseThrow().compareTo(revised.handedOver()) <= 0;
    if (reopened) {
      lifecycle.reopenUnderfulfilledMoneyOrder(order);
    } else if (completes) {
      lifecycle.completeJobOrderWithinTransaction(order);
    }
    effects.correctionRecorded(
        order,
        parties,
        marker,
        actor,
        reopened || completes,
        recipientId != null,
        entry.changesCredit());
  }
}
