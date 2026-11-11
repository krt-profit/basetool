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
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Records exact, manually reported provision under the order's edit boundary. */
@Service
@RequiredArgsConstructor
@Transactional
public class JobOrderMoneyCommands {

  private final JobOrderService orders;
  private final JobOrderEditor editor;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyLedger ledger;
  private final AuthHelperService auth;
  private final JobOrderAccessPolicy access;
  private final JobOrderResponsibilityGuard responsibility;
  private final JobOrderMoneyHolders holderChoices;
  private final JobOrderMoneyQueries queries;
  private final JobOrderMoneyIdentityLock identityLock;
  private final JobOrderMoneyEffects effects;

  /**
   * Creates an aUEC order and its requested amount in one transaction.
   *
   * @param dto the minimal order request
   * @return the new order with shared lifecycle and participation fields
   */
  public JobOrderDto create(CreateAuecJobOrderDto dto) {
    BigDecimal target = WholeAuec.positive(dto.target());
    JobOrderDto order = orders.createAuecJobOrder(dto);
    targets.save(new JobOrderMoneyTarget(order.id(), target));
    return order;
  }

  /**
   * Records the caller's reported provision or replays the same accepted attempt.
   *
   * @param orderId the aUEC order
   * @param dto the submitted provision and observed holder revision
   * @return the recorded result, or empty for a durable refusal with no monetary effect
   */
  public Optional<JobOrderMoneyOutcome> provide(UUID orderId, ProvideJobOrderMoneyDto dto) {
    BigDecimal amount = WholeAuec.positive(dto.amount());
    JobOrderMoneySelection selection = dto.selection();
    UUID actor =
        auth.currentUserId()
            .orElseThrow(() -> new AccessDeniedException("An active member account is required."));
    identityLock.lockAccounts(actor, dto.holderUserId());
    return editor.editMoney(orderId, order -> provideChecked(order, dto, actor, amount, selection));
  }

  /**
   * Shows the exact provision effect on the selected holder's current reported funds.
   *
   * @param orderId the aUEC order
   * @param dto the observed holder and proposed terms
   * @return the effect without creating a holder, operation, or audit entry
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public JobOrderMoneyProvisionPreview preview(UUID orderId, PreviewJobOrderMoneyProvisionDto dto) {
    JobOrder order = queries.requireReadableMoneyOrder(orderId);
    responsibility.assertWritable(order);
    final UUID actor = requireActor();
    JobOrderMoneySelection selection = dto.selection();
    authorizeSelection(order, selection);
    requireBankFree(dto.bankHandling());
    if (!order.getStatus().isActive()) {
      throw new BusinessConflictException("orders.money.provision.inactive");
    }
    BigDecimal amount = WholeAuec.positive(dto.amount());
    JobOrderMoneyHolder holder;
    try {
      holder = holderChoices.resolve(orderId, actor, selection);
    } catch (JobOrderMoneyHolders.UnavailableHolderException unavailable) {
      throw unavailable.previewProblem();
    }
    requireHolderVersion(holder, dto.holderVersion());
    BigDecimal current = ledger.read(orderId).balance(holder.getId());
    return new JobOrderMoneyProvisionPreview(
        amount.toPlainString(),
        holderChoices.nameOf(holder),
        BigDecimal.ZERO.toPlainString(),
        dto.bankHandling(),
        current.add(amount).toPlainString());
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> provideChecked(
      JobOrder order,
      ProvideJobOrderMoneyDto dto,
      UUID actor,
      BigDecimal amount,
      JobOrderMoneySelection selection) {
    if (holderChoices.activeUser(actor).isEmpty()) {
      throw new AccessDeniedException("An active member account is required.");
    }
    authorizeSelection(order, selection);
    var existing = operations.findByOrderIdAndOperationId(order.getId(), dto.operationId());
    if (existing.isPresent()) {
      return replay(order, existing.orElseThrow(), actor, amount, dto.bankHandling(), selection);
    }
    if (operations.isRefused(order.getId(), dto.operationId())) {
      return refused(order);
    }
    requireBankFree(dto.bankHandling());
    if (!order.getStatus().isActive()) {
      return reserveRefusal(order, dto.operationId());
    }
    JobOrderMoneyHolder holder;
    try {
      holder = holderChoices.resolve(order.getId(), actor, selection);
      requireHolderVersion(holder, dto.holderVersion());
    } catch (ObjectOptimisticLockingFailureException
        | JobOrderMoneyHolders.UnavailableHolderException unavailableBasis) {
      return reserveRefusal(order, dto.operationId());
    }
    boolean newLinkedHolder = holder.getId() == null && holder.getUserId() != null;
    holder.recordChange();
    UUID holderId = Objects.requireNonNull(holders.save(holder).getId());
    JobOrderMoneyOperation operation =
        operations.saveAndFlush(
            new JobOrderMoneyOperation(
                order.getId(),
                dto.operationId(),
                holderId,
                actor,
                selection,
                dto.bankHandling(),
                amount));
    boolean started = order.getStatus() == JobOrderStatus.OPEN;
    if (started) {
      JobOrderService.changeLifecycle(order, JobOrderStatus.IN_PROGRESS);
    }
    effects.provisionRecorded(order, holder, operation, actor, started, newLinkedHolder);
    JobOrderEdit audit =
        JobOrderEdit.audited(
            order,
            AuditEventType.JOB_ORDER_MONEY_PROVIDED,
            AuditDetails.of("operationId", operation.getOperationId()).with("amount", amount));
    return new JobOrderEditor.MoneyEdit<>(
        Optional.of(queries.projectOutcome(operation, holder)), audit);
  }

  private static JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> refused(JobOrder order) {
    return new JobOrderEditor.MoneyEdit<>(Optional.empty(), JobOrderEdit.unchanged(order));
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> reserveRefusal(
      JobOrder order, UUID operationId) {
    operations.reserveRefusal(order.getId(), operationId);
    return refused(order);
  }

  private UUID requireActor() {
    return auth.currentUserId()
        .flatMap(holderChoices::activeUser)
        .map(User::getId)
        .orElseThrow(() -> new AccessDeniedException("An active member account is required."));
  }

  private void authorizeSelection(JobOrder order, JobOrderMoneySelection selection) {
    if (selection.kind() != JobOrderMoneySelection.Kind.SELF
        && !access.canTransferResponsibility(order)) {
      throw new AccessDeniedException(
          "Only the current responsible writer may provide for others.");
    }
  }

  private static void requireBankFree(boolean bankHandling) {
    if (bankHandling) {
      throw new BadRequestException("orders.money.bankHandling.unavailable");
    }
  }

  private static void requireHolderVersion(JobOrderMoneyHolder holder, @Nullable Long version) {
    OptimisticLock.check(holder.getRevision(), version, JobOrderMoneyHolder.class, holder.getId());
  }

  private JobOrderEditor.MoneyEdit<Optional<JobOrderMoneyOutcome>> replay(
      JobOrder order,
      JobOrderMoneyOperation operation,
      UUID actor,
      BigDecimal amount,
      boolean bankHandling,
      JobOrderMoneySelection selection) {
    if (!Objects.equals(actor, operation.getActorId())) {
      throw new AccessDeniedException("Caller may not replay this operation.");
    }
    if (operation.getKind() != JobOrderMoneyKind.PROVISION
        || amount.compareTo(operation.getAmount()) != 0
        || bankHandling != operation.isBankHandling()
        || !operation.matchesSelection(selection)) {
      throw new BusinessConflictException("orders.money.operation.termsMismatch");
    }
    JobOrderMoneyHolder holder = holders.findById(operation.getHolderId()).orElseThrow();
    return new JobOrderEditor.MoneyEdit<>(
        Optional.of(queries.projectOutcome(operation, holder)), JobOrderEdit.unchanged(order));
  }
}
