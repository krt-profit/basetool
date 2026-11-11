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

import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy;
import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy.RateSnapshot;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Resolves payment parties, applies their grants and calculates the exact posting at one basis. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyMovementBasis {

  private final JobOrderMoneyHolders holderChoices;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyLedger ledger;
  private final TransferFeePolicy fees;

  Resolved resolve(
      JobOrder order,
      MoveJobOrderMoneyDto dto,
      JobOrderMoneyMovementTerms terms,
      UUID actor,
      @Nullable RateSnapshot expectedRate) {
    UUID orderId = order.getId();
    var sourceSelection =
        new JobOrderMoneySelection(JobOrderMoneySelection.Kind.EXISTING, terms.sourceId(), null);
    JobOrderMoneyHolder source = holderChoices.resolve(orderId, actor, sourceSelection);
    if (terms.kind() == JobOrderMoneyKind.COLLECTION
        && terms.selection().kind() == JobOrderMoneySelection.Kind.LINKED) {
      authorize(order, terms, actor, source.getUserId(), terms.selection().reference());
    }
    JobOrderMoneyHolder destination =
        terms.kind().delivers() ? null : holderChoices.resolve(orderId, actor, terms.selection());
    var recipient =
        terms.recipientId() == null ? null : recipients.require(orderId, terms.recipientId());
    var receivingParty =
        recipient == null
            ? new JobOrderMoneyRecipients.Party(
                Objects.requireNonNull(destination).getId(),
                destination.getUserId(),
                holderChoices.nameOf(destination))
            : recipients.party(recipient);
    authorize(order, terms, actor, source.getUserId(), receivingParty.accountId());

    requireVersion(source, dto.sourceVersion());
    if (destination != null) {
      requireVersion(destination, dto.destinationVersion());
    }
    if (recipient != null && (!recipient.isActive() || !receivingParty.usable())) {
      throw new UnavailableBasis(new BusinessConflictException("orders.money.recipient.inactive"));
    }
    checkPurpose(source, receivingParty, terms.kind());
    var current = ledger.read(orderId);
    var target = terms.kind().delivers() ? targets.findById(orderId).orElseThrow() : null;
    if (target != null) {
      OptimisticLock.check(
          target.getVersion(), dto.targetVersion(), JobOrderMoneyTarget.class, orderId);
    }
    RateSnapshot rate =
        terms.kind() == JobOrderMoneyKind.PURPOSE_HANDOVER
            ? new RateSnapshot(BigDecimal.ZERO)
            : fees.snapshot();
    if (expectedRate != null && !expectedRate.equals(rate)) {
      throw new UnavailableBasis(
          new BusinessConflictException("orders.money.movement.rateRequired"));
    }
    BigDecimal fee = fees.feeOn(terms.amount(), rate);
    BigDecimal held = current.balance(source.getId());
    BigDecimal principalShortfall = terms.amount().subtract(held).max(BigDecimal.ZERO);
    BigDecimal sourceAfter = held.subtract(terms.amount()).subtract(fee);
    if (terms.provision().compareTo(principalShortfall.add(fee)) > 0) {
      throw new BadRequestException("orders.money.movement.provision.excess");
    }
    BigDecimal delivered = current.handedOver();
    if (target != null) {
      delivered = delivered.add(terms.amount());
    }
    return new Resolved(
        source,
        destination,
        receivingParty,
        rate,
        fee,
        sourceAfter.add(terms.provision()),
        delivered,
        target == null ? null : target.getAmount());
  }

  void authorize(
      JobOrder order,
      JobOrderMoneyMovementTerms terms,
      UUID actor,
      @Nullable UUID sourceAccount,
      @Nullable UUID destinationAccount) {
    boolean canProvide = access.responsible(order) || actor.equals(sourceAccount);
    if (!canProvide
        && !actor.equals(destinationAccount)
        && !(terms.kind().delivers() && access.requester(order))) {
      throw new AccessDeniedException("Caller may not record this movement.");
    }
    if (!canProvide && terms.provision().signum() > 0) {
      throw new AccessDeniedException("Caller may not provide funds for this source.");
    }
  }

  private static void checkPurpose(
      JobOrderMoneyHolder source,
      JobOrderMoneyRecipients.Party destination,
      JobOrderMoneyKind kind) {
    var sendingParty = new JobOrderMoneyRecipients.Party(source.getId(), source.getUserId(), null);
    if (sendingParty.sameKnownPerson(destination) != (kind == JobOrderMoneyKind.PURPOSE_HANDOVER)) {
      throw new UnavailableBasis(new BadRequestException("orders.money.movement.purpose.invalid"));
    }
  }

  private static void requireVersion(JobOrderMoneyHolder holder, @Nullable Long version) {
    OptimisticLock.check(holder.getRevision(), version, JobOrderMoneyHolder.class, holder.getId());
  }

  /** Carries an invalid preview basis that becomes a durable refusal on submission. */
  static final class UnavailableBasis extends RuntimeException {

    private final RuntimeException previewProblem;

    private UnavailableBasis(RuntimeException previewProblem) {
      super(previewProblem);
      this.previewProblem = previewProblem;
    }

    RuntimeException previewProblem() {
      return previewProblem;
    }
  }

  /**
   * Checked posting with the current authorization and holder/target basis.
   *
   * @param source the original debited holder
   * @param destination the credited collection holder, if applicable
   * @param recipient the resolved receiving identity
   * @param rate the immutable effective fee basis
   * @param fee exact whole-aUEC fee
   * @param sourceAfter exact amount after top-up, principal and fee
   * @param delivered net handover after the movement
   * @param target target affected by a requester handover
   */
  record Resolved(
      JobOrderMoneyHolder source,
      @Nullable JobOrderMoneyHolder destination,
      JobOrderMoneyRecipients.Party recipient,
      RateSnapshot rate,
      BigDecimal fee,
      BigDecimal sourceAfter,
      BigDecimal delivered,
      @Nullable BigDecimal target) {

    boolean completes() {
      return target != null && delivered.compareTo(target) >= 0;
    }

    JobOrderMoneyMovementPreview preview(
        JobOrderMoneyMovementTerms terms, @Nullable String sourceName) {
      return new JobOrderMoneyMovementPreview(
          terms.amount().toPlainString(),
          fee.toPlainString(),
          rate.rate().toPlainString(),
          terms.provision().toPlainString(),
          sourceAfter.max(BigDecimal.ZERO).toPlainString(),
          sourceName,
          recipient.name(),
          target == null ? "0" : delivered.subtract(target).max(BigDecimal.ZERO).toPlainString(),
          sourceAfter.negate().max(BigDecimal.ZERO).toPlainString());
    }
  }
}
