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

import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy.RateSnapshot;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Records the authorised settlement facts under the caller's order guard. */
@Component
@RequiredArgsConstructor
class JobOrderMoneySettlementWriter {

  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderService lifecycle;
  private final JobOrderMoneyEffects effects;
  private final JobOrderMoneyQueries queries;

  JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> post(
      JobOrder order,
      @Nullable JobOrderMoneyHolder source,
      @Nullable JobOrderMoneyHolder destination,
      Effect effect) {
    final boolean createsLink =
        destination != null && destination.getId() == null && destination.getUserId() != null;
    UUID destinationId = null;
    if (destination != null) {
      destination.recordChange();
      destinationId = Objects.requireNonNull(holders.save(destination).getId());
    }
    if (source != null) {
      source.recordChange();
    }
    var holding = source == null ? Objects.requireNonNull(destination) : source;
    RateSnapshot rate = effect.rate();
    var fact =
        operations.saveAndFlush(
            new JobOrderMoneyOperation(
                order.getId(),
                effect.operationId(),
                Objects.requireNonNull(holding.getId()),
                effect.originalActor(),
                effect.selection(),
                null,
                effect.attemptId() != null,
                effect.amount(),
                effect.kind(),
                destinationId,
                effect.recipientId(),
                rate == null ? null : effect.provision(),
                rate == null ? null : rate.rate(),
                rate == null ? null : effect.fee()));
    if (effect.reopens()) {
      lifecycle.reopenUnderfulfilledMoneyOrder(order);
    }
    effects.movementRecorded(
        order,
        fact,
        effect.payerAccount(),
        destination == null ? null : destination.getUserId(),
        effect.decisionActor(),
        effect.reopens(),
        createsLink);
    var details =
        AuditDetails.of("operationId", effect.operationId())
            .with("kind", effect.kind())
            .with("amount", effect.amount())
            .with("fee", effect.fee())
            .with("provision", effect.provision());
    if (effect.attemptId() != null) {
      details = details.with("attemptId", effect.attemptId());
    }
    var audit = JobOrderEdit.audited(order, AuditEventType.JOB_ORDER_MONEY_MOVED, details);
    return new JobOrderEditor.MoneyEdit<>(
        Optional.of(queries.projectOutcome(fact, holding)), audit);
  }

  /** The final settlement basis including the original recorder and current decision actor. */
  record Effect(
      UUID operationId,
      @Nullable UUID originalActor,
      UUID decisionActor,
      JobOrderMoneyKind kind,
      JobOrderMoneySelection selection,
      @Nullable UUID recipientId,
      @Nullable UUID payerAccount,
      BigDecimal amount,
      BigDecimal provision,
      @Nullable RateSnapshot rate,
      BigDecimal fee,
      @Nullable UUID attemptId,
      boolean reopens) {}
}
