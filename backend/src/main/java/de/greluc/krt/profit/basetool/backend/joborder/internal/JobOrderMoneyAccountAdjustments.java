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
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Records later losses and releases on the order's Bank account positions. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyAccountAdjustments {

  private final JobOrderEditor editor;
  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyIdentityLock identities;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneySettlementBasis basis;
  private final JobOrderMoneyQueries queries;
  private final JobOrderMoneyEffects effects;

  JobOrderMoneyMovementPreview preview(
      JobOrder order, JobOrderMoneySettlementTerms terms, @Nullable Long sourceVersion) {
    basis.authorize(order, terms.kind(), access.requireActiveActor(), null);
    try {
      var source = resolve(order, terms, sourceVersion);
      return new JobOrderMoneyMovementPreview(
          terms.amount().toPlainString(),
          "0",
          "0",
          "0",
          source.remaining().toPlainString(),
          null,
          null,
          "0",
          "0",
          null,
          false,
          false,
          null);
    } catch (JobOrderMoneySettlementBasis.UnavailableSettlement unavailable) {
      throw unavailable.previewProblem();
    }
  }

  Optional<JobOrderMoneyOutcome> record(
      UUID orderId,
      UUID operationId,
      JobOrderMoneySettlementTerms terms,
      @Nullable Long sourceVersion) {
    UUID actor = access.actorId();
    identities.lockAccounts(actor);
    return editor.editMoney(
        orderId, order -> recordGuarded(order, operationId, terms, sourceVersion, actor));
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> recordGuarded(
      JobOrder order,
      UUID operationId,
      JobOrderMoneySettlementTerms terms,
      @Nullable Long sourceVersion,
      UUID actor) {
    basis.authorize(order, terms.kind(), access.requireActiveActor(), null);
    UUID orderId = order.getId();
    var accepted = operations.findByOrderIdAndOperationId(orderId, operationId);
    if (accepted.isPresent()) {
      var fact = accepted.orElseThrow();
      if (!actor.equals(fact.getActorId())) {
        throw new AccessDeniedException("Caller may not replay this operation.");
      }
      if (fact.isBankHandling()
          || !Objects.equals(terms.sourceId(), fact.getAccountHoldingId())
          || terms.kind() != fact.getKind()
          || terms.amount().compareTo(fact.getAmount()) != 0) {
        throw new BusinessConflictException("orders.money.operation.termsMismatch");
      }
      return new JobOrderEditor.MoneyEdit<>(
          Optional.of(queries.projectOutcome(fact)), JobOrderEdit.unchanged(order));
    }
    if (operations.isRefused(orderId, operationId)) {
      return new JobOrderEditor.MoneyEdit<>(Optional.empty(), JobOrderEdit.unchanged(order));
    }
    ledger.requireIndependentOperation(orderId, operationId);
    Source source;
    try {
      source = resolve(order, terms, sourceVersion);
    } catch (JobOrderMoneySettlementBasis.UnavailableSettlement
        | ObjectOptimisticLockingFailureException unavailable) {
      operations.reserveRefusal(orderId, operationId);
      return new JobOrderEditor.MoneyEdit<>(Optional.empty(), JobOrderEdit.unchanged(order));
    }
    source.holding().recordChange();
    var fact =
        operations.saveAndFlush(
            JobOrderMoneyOperation.accountOperation(
                orderId,
                operationId,
                Objects.requireNonNull(source.holding().getId()),
                actor,
                null,
                terms.kind(),
                null,
                terms.amount()));
    effects.accountMoneyChanged(order, false);
    var details =
        AuditDetails.of("operationId", operationId)
            .with("kind", terms.kind())
            .with("amount", terms.amount())
            .with("fee", BigDecimal.ZERO)
            .with("provision", BigDecimal.ZERO);
    var audit = JobOrderEdit.audited(order, AuditEventType.JOB_ORDER_MONEY_MOVED, details);
    return new JobOrderEditor.MoneyEdit<>(Optional.of(queries.projectOutcome(fact)), audit);
  }

  private Source resolve(
      JobOrder order, JobOrderMoneySettlementTerms terms, @Nullable Long sourceVersion) {
    JobOrderMoneySettlementBasis.assertSettlementStatus(order);
    UUID sourceId = Objects.requireNonNull(terms.sourceId());
    var current = ledger.read(order.getId());
    var source = current.accountHoldings().get(sourceId);
    if (source == null) {
      throw new JobOrderMoneySettlementBasis.UnavailableSettlement(
          "orders.money.settlement.selection.invalid");
    }
    OptimisticLock.check(
        source.getRevision(), sourceVersion, JobOrderMoneyAccountHolding.class, sourceId);
    BigDecimal remaining = current.availableAccountBalance(sourceId).subtract(terms.amount());
    if (remaining.signum() < 0) {
      throw new JobOrderMoneySettlementBasis.UnavailableSettlement(
          "orders.money.settlement.insufficient");
    }
    return new Source(source, remaining);
  }

  private record Source(JobOrderMoneyAccountHolding holding, BigDecimal remaining) {}
}
