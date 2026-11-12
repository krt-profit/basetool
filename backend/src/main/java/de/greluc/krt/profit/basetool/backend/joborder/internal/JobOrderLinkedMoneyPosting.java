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
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Applies the frozen order effect with the current holding, target and terminal basis. */
@Component
@RequiredArgsConstructor
class JobOrderLinkedMoneyPosting {
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyProvisionWriter provisions;
  private final JobOrderMoneyMovementWriter movements;
  private final JobOrderMoneySettlementWriter settlements;

  JobOrderEdit post(JobOrder order, JobOrderLinkedMoneyAttempt attempt, UUID actor) {
    var holder = holders.findById(attempt.personalHolderId()).orElseThrow();
    if (attempt.getKind() == JobOrderMoneyKind.PROVISION) {
      var terms =
          new JobOrderMoneyProvisionWriter.Terms(
              attempt.getOperationId(),
              attempt.getActorId(),
              actor,
              attempt.selection(),
              attempt.getCommitmentId(),
              true,
              attempt.getAmount(),
              false,
              attempt.getId());
      return provisions.post(order, holder, terms).edit();
    }
    var current = ledger.read(order.getId());
    if (current
            .availableBalance(attempt.getHolderId(), attempt.getId())
            .compareTo(attempt.getSourceBinding())
        < 0) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    var destination =
        attempt.getDestinationHolderId() == null
            ? null
            : holders.findById(attempt.getDestinationHolderId()).orElseThrow();
    UUID recipient = attempt.getRecipientId();
    if (attempt.getKind() == JobOrderMoneyKind.SETTLEMENT
        || attempt.getKind() == JobOrderMoneyKind.REQUESTER_RETURN) {
      return settle(order, attempt, actor, current, holder, destination);
    }
    UUID receivingUser =
        destination == null
            ? (recipient == null ? null : recipients.accountFor(order.getId(), recipient))
            : destination.getUserId();
    boolean completes =
        attempt.getKind().delivers()
            && targets
                    .amountFor(order.getId())
                    .orElseThrow()
                    .compareTo(current.handedOver().add(attempt.getAmount()))
                <= 0;
    var movement =
        new JobOrderMoneyMovementTerms(
            attempt.getKind(),
            attempt.personalHolderId(),
            attempt.selection(),
            recipient,
            attempt.getAmount(),
            attempt.getProvision(),
            attempt.getCommitmentId());
    var terms =
        new JobOrderMoneyMovementWriter.Terms(
            attempt.getOperationId(),
            attempt.getActorId(),
            actor,
            movement,
            receivingUser,
            attempt.getKind() == JobOrderMoneyKind.PURPOSE_HANDOVER
                ? BigDecimal.ZERO
                : attempt.getRate(),
            attempt.orderFee(),
            completes,
            attempt.getId());
    return movements.post(order, holder, destination, terms).edit();
  }

  private JobOrderEdit settle(
      JobOrder order,
      JobOrderLinkedMoneyAttempt attempt,
      UUID actor,
      JobOrderMoneyLedger.Ledger current,
      JobOrderMoneyHolder source,
      @Nullable JobOrderMoneyHolder destination) {
    boolean returning = attempt.getKind() == JobOrderMoneyKind.REQUESTER_RETURN;
    UUID recipientId = attempt.getRecipientId();
    if (returning
        && current
                .availableForPersonalReturn(Objects.requireNonNull(recipientId), attempt.getId())
                .compareTo(attempt.getAmount())
            < 0) {
      throw JobOrderLinkedMoneyAttempt.conflict();
    }
    boolean reopens =
        returning
            && order.getStatus() == JobOrderStatus.COMPLETED
            && targets
                    .amountFor(order.getId())
                    .orElseThrow()
                    .compareTo(current.handedOver().subtract(attempt.getAmount()))
                > 0;
    UUID payer =
        recipientId == null
            ? source.getUserId()
            : recipients.accountFor(order.getId(), recipientId);
    var effect =
        new JobOrderMoneySettlementWriter.Effect(
            attempt.getOperationId(),
            attempt.getActorId(),
            actor,
            attempt.getKind(),
            attempt.selection(),
            recipientId,
            payer,
            attempt.getAmount(),
            attempt.getProvision(),
            attempt.rateSnapshot(),
            attempt.orderFee(),
            attempt.getId(),
            reopens);
    return settlements
        .post(
            order,
            attempt.personalHolderId().equals(attempt.getDestinationHolderId()) ? null : source,
            destination,
            effect)
        .edit();
  }
}
