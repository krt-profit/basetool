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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderEditor.MoneyPairEdit;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import java.math.BigDecimal;
import java.util.List;
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

/** Moves the same player's purpose-bound aUEC between two orders. */
@Service
@RequiredArgsConstructor
@Transactional
public class JobOrderMoneyReassignment {

  private final JobOrderEditor editor;
  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyQueries queries;
  private final JobOrderQueryService orders;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyIdentityLock identityLock;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyEffects effects;
  private final JobOrderMoneyHolders holderChoices;

  /**
   * Calculates both balances at the observed revisions without changing either order.
   *
   * @param orderId the source order
   * @param dto the proposed reassignment
   * @return the exact fee-free purpose change
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public JobOrderMoneyReassignmentPreview preview(UUID orderId, ReassignJobOrderMoneyDto dto) {
    final BigDecimal amount = amount(orderId, dto);
    JobOrder source = queries.requireReadableMoneyOrder(orderId);
    JobOrder destination = queries.requireReadableMoneyOrder(dto.destinationOrderId());
    UUID actor = access.requireActiveActor();
    Parties parties = parties(source, destination, dto);
    authorize(source, destination, parties, actor);
    if (queries.responsibilityInvalid(source) || queries.responsibilityInvalid(destination)) {
      throw new BadRequestException("orders.responsibility.invalid");
    }
    if (!acceptsReassignment(source, destination)) {
      throw new BusinessConflictException("orders.money.movement.inactive");
    }
    if (parties.retired()) {
      throw new BusinessConflictException("orders.money.holder.linkChanged");
    }
    checkSamePerson(parties, dto);
    checkVersions(parties, dto);
    Balances balances = balances(source, destination, parties, amount);
    if (balances.sourceAfter().signum() < 0) {
      throw new BusinessConflictException("orders.money.reassignment.insufficient");
    }
    return new JobOrderMoneyReassignmentPreview(
        amount.toPlainString(),
        "0",
        "0",
        balances.sourceAfter().toPlainString(),
        balances.destinationAfter().toPlainString(),
        holderChoices.nameOf(parties.source()),
        holderChoices.nameOf(parties.destination()));
  }

  /**
   * Resolves an exact order number and its selectable holders under current authority.
   *
   * @param orderId the source order
   * @param sourceHolderId the holder moving the funds
   * @param number the destination display number
   * @return the active destination and only the holder choices relevant to this person
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public JobOrderMoneyReassignmentDestination destination(
      UUID orderId, UUID sourceHolderId, int number) {
    JobOrder source = queries.requireReadableMoneyOrder(orderId);
    UUID actor = access.requireActiveActor();
    JobOrderMoneyHolder sender = requireHolder(orderId, sourceHolderId);
    UUID account = sender.getUserId();
    requireSideAuthority(source, actor, account);
    if (sender.getMergedIntoId() != null) {
      throw new BusinessConflictException("orders.money.holder.linkChanged");
    }
    if (account == null && !namedAccountless(sender)) {
      throw new BadRequestException("orders.money.reassignment.samePerson");
    }
    UUID destinationId =
        Entities.require(
            orders.findByNumberPrefix(Integer.toString(number), 1).stream()
                .filter(hit -> hit.displayId() == number)
                .map(JobOrderNumberHitDto::id)
                .findFirst(),
            "orders.money.reassignment.notFound");
    if (orderId.equals(destinationId)) {
      throw new BadRequestException("orders.money.reassignment.order");
    }
    JobOrder destination = queries.requireReadableMoneyOrder(destinationId);
    requireSideAuthority(destination, actor, account);
    if (queries.responsibilityInvalid(source) || queries.responsibilityInvalid(destination)) {
      throw new BadRequestException("orders.responsibility.invalid");
    }
    if (!acceptsReassignment(source, destination)) {
      throw new BusinessConflictException("orders.money.movement.inactive");
    }
    List<JobOrderMoneyHolder> choices =
        account == null
            ? holders.findAllByOrderIdOrderById(destinationId).stream()
                .filter(holder -> holder.getMergedIntoId() == null && namedAccountless(holder))
                .toList()
            : holders.findByOrderIdAndUserId(destinationId, account).stream().toList();
    return new JobOrderMoneyReassignmentDestination(
        destinationId,
        choices.stream()
            .map(holder ->
                new JobOrderMoneyReassignmentHolder(
                    Objects.requireNonNull(holder.getId()),
                    holderChoices.nameOf(holder),
                    holder.getRevision()))
            .toList());
  }

  /**
   * Posts both legs once, or returns the recorded source leg for a matching replay.
   *
   * @param orderId the source order
   * @param dto the submitted terms and both holder revisions
   * @return the source leg, or empty when the identity was durably refused
   */
  public Optional<JobOrderMoneyOutcome> record(UUID orderId, ReassignJobOrderMoneyDto dto) {
    final BigDecimal amount = amount(orderId, dto);
    UUID operationId = dto.operationId();
    if (operationId == null) {
      throw new BadRequestException("orders.money.movement.operationRequired");
    }
    UUID actor = access.actorId();
    UUID sourceAccount = holders.accountFor(orderId, dto.sourceHolderId()).orElse(null);
    UUID destinationAccount =
        dto.destinationHolderId() == null
            ? sourceAccount
            : holders.accountFor(dto.destinationOrderId(), dto.destinationHolderId()).orElse(null);
    Accounts accounts = new Accounts(sourceAccount, destinationAccount);
    identityLock.lockAccounts(actor, accounts.source(), accounts.destination());
    Attempt attempt = new Attempt(operationId, actor, accounts, dto, amount);
    return editor.editMoneyPair(
        orderId,
        dto.destinationOrderId(),
        (source, destination) -> recordLocked(source, destination, attempt));
  }

  private MoneyPairEdit<Optional<JobOrderMoneyOutcome>> recordLocked(
      JobOrder source, JobOrder destination, Attempt attempt) {
    access.requireActiveActor();
    UUID operationId = attempt.id();
    var priorSource = operations.findByOrderIdAndOperationId(source.getId(), operationId);
    var priorDestination = operations.findByOrderIdAndOperationId(destination.getId(), operationId);
    if (priorSource.isPresent() || priorDestination.isPresent()) {
      return replay(
          source, destination, attempt, priorSource.orElse(null), priorDestination.orElse(null));
    }

    Parties parties = parties(source, destination, attempt.dto());
    authorize(source, destination, parties, attempt.actor());
    if (operations.isRefused(source.getId(), operationId)
        || operations.isRefused(destination.getId(), operationId)) {
      return unchanged(source, destination, Optional.empty());
    }
    if (!parties.accounts().equals(attempt.accounts())
        || parties.retired()
        || !acceptsReassignment(source, destination)) {
      return refuse(source, destination, operationId);
    }
    checkSamePerson(parties, attempt.dto());
    try {
      checkVersions(parties, attempt.dto());
    } catch (ObjectOptimisticLockingFailureException stale) {
      return refuse(source, destination, operationId);
    }
    if (balances(source, destination, parties, attempt.amount()).sourceAfter().signum() < 0) {
      return refuse(source, destination, operationId);
    }
    return post(source, destination, parties, attempt);
  }

  private MoneyPairEdit<Optional<JobOrderMoneyOutcome>> replay(
      JobOrder source,
      JobOrder destination,
      Attempt attempt,
      @Nullable JobOrderMoneyOperation outflow,
      @Nullable JobOrderMoneyOperation inflow) {
    if (outflow == null
        || inflow == null
        || outflow.getKind() != JobOrderMoneyKind.PURPOSE_OUTFLOW
        || inflow.getKind() != JobOrderMoneyKind.PURPOSE_INFLOW
        || !destination.getId().equals(outflow.getPeerOrderId())
        || !source.getId().equals(inflow.getPeerOrderId())) {
      throw termsMismatch();
    }
    if (!attempt.actor().equals(outflow.getActorId())
        || !attempt.actor().equals(inflow.getActorId())) {
      throw new AccessDeniedException("Caller may not replay this operation.");
    }
    var parties =
        new Parties(
            currentHolder(source.getId(), outflow.getHolderId()),
            currentHolder(destination.getId(), inflow.getHolderId()));
    authorize(source, destination, parties, attempt.actor());
    if (attempt.amount().compareTo(outflow.getAmount()) != 0
        || attempt.amount().compareTo(inflow.getAmount()) != 0
        || !attempt.dto().sourceHolderId().equals(outflow.getHolderId())
        || !matchesDestination(attempt.dto(), parties, inflow)) {
      throw termsMismatch();
    }
    return unchanged(
        source, destination, Optional.of(queries.projectOutcome(outflow, parties.source())));
  }

  private static boolean matchesDestination(
      ReassignJobOrderMoneyDto dto, Parties parties, JobOrderMoneyOperation inflow) {
    UUID selected = dto.destinationHolderId();
    boolean confirmed = Boolean.TRUE.equals(dto.confirmSamePerson());
    return switch (inflow.getSelectionKind()) {
      case ACCOUNTLESS -> selected == null && confirmed;
      case EXISTING -> inflow.getHolderId().equals(selected) && confirmed;
      case LINKED ->
          selected == null
              ? parties.accounts().sameAccount()
              : selected.equals(inflow.getHolderId());
      case SELF -> false;
    };
  }

  private MoneyPairEdit<Optional<JobOrderMoneyOutcome>> post(
      JobOrder source, JobOrder destination, Parties parties, Attempt attempt) {
    JobOrderMoneyHolder sourceHolder = parties.source();
    JobOrderMoneyHolder destinationHolder = parties.destination();
    final boolean createsDestination = destinationHolder.getId() == null;
    final UUID destinationAccount = destinationHolder.getUserId();
    sourceHolder.recordChange();
    destinationHolder.recordChange();
    UUID sourceId = source.getId();
    UUID destinationId = destination.getId();
    var outflow =
        operations.save(
            JobOrderMoneyOperation.reassignment(
                sourceId,
                destinationId,
                attempt.id(),
                Objects.requireNonNull(sourceHolder.getId()),
                attempt.actor(),
                attempt.amount(),
                JobOrderMoneyKind.PURPOSE_OUTFLOW,
                JobOrderMoneySelection.Kind.EXISTING));
    operations.save(
        JobOrderMoneyOperation.reassignment(
            destinationId,
            sourceId,
            attempt.id(),
            Objects.requireNonNull(holders.save(destinationHolder).getId()),
            attempt.actor(),
            attempt.amount(),
            JobOrderMoneyKind.PURPOSE_INFLOW,
            destinationSelection(createsDestination, destinationAccount)));
    boolean started = destination.getStatus() == JobOrderStatus.OPEN;
    if (started) {
      JobOrderService.changeLifecycle(destination, JobOrderStatus.IN_PROGRESS);
    }
    effects.reassignmentRecorded(
        source,
        destination,
        outflow,
        sourceHolder.getUserId(),
        attempt.actor(),
        started,
        createsDestination && destinationAccount != null);
    return new MoneyPairEdit<>(
        Optional.of(queries.projectOutcome(outflow, sourceHolder)),
        audit(source, attempt, JobOrderMoneyKind.PURPOSE_OUTFLOW),
        audit(destination, attempt, JobOrderMoneyKind.PURPOSE_INFLOW));
  }

  private static JobOrderMoneySelection.Kind destinationSelection(
      boolean createsDestination, @Nullable UUID account) {
    if (account != null) {
      return JobOrderMoneySelection.Kind.LINKED;
    }
    return createsDestination
        ? JobOrderMoneySelection.Kind.ACCOUNTLESS
        : JobOrderMoneySelection.Kind.EXISTING;
  }

  private static JobOrderEdit audit(JobOrder order, Attempt attempt, JobOrderMoneyKind kind) {
    return JobOrderEdit.audited(
        order,
        AuditEventType.JOB_ORDER_MONEY_REASSIGNED,
        AuditDetails.of("operationId", attempt.id())
            .with("kind", kind)
            .with("amount", attempt.amount()));
  }

  private Parties parties(JobOrder source, JobOrder destination, ReassignJobOrderMoneyDto dto) {
    JobOrderMoneyHolder sender = requireHolder(source.getId(), dto.sourceHolderId());
    if (dto.destinationHolderId() != null) {
      return new Parties(sender, requireHolder(destination.getId(), dto.destinationHolderId()));
    }
    UUID account = sender.getUserId();
    UUID destinationId = destination.getId();
    if (account == null) {
      if (!namedAccountless(sender) || !Boolean.TRUE.equals(dto.confirmSamePerson())) {
        throw new BadRequestException("orders.money.reassignment.samePerson");
      }
      return new Parties(
          sender,
          JobOrderMoneyHolder.named(
              destinationId, Objects.requireNonNull(sender.getAccountlessName())));
    }
    JobOrderMoneyHolder receiver =
        holders
            .findByOrderIdAndUserId(destinationId, account)
            .orElseGet(() -> JobOrderMoneyHolder.linked(destinationId, account));
    return new Parties(sender, receiver);
  }

  private JobOrderMoneyHolder requireHolder(UUID orderId, UUID holderId) {
    return holders
        .findByOrderIdAndId(orderId, holderId)
        .orElseThrow(() -> new BadRequestException("orders.money.holder.notFound"));
  }

  private JobOrderMoneyHolder currentHolder(UUID orderId, UUID holderId) {
    JobOrderMoneyHolder holder = requireHolder(orderId, holderId);
    return holder.getMergedIntoId() == null
        ? holder
        : requireHolder(orderId, holder.getMergedIntoId());
  }

  private void authorize(JobOrder source, JobOrder destination, Parties parties, UUID actor) {
    requireSideAuthority(source, actor, parties.source().getUserId());
    requireSideAuthority(destination, actor, parties.destination().getUserId());
  }

  private void requireSideAuthority(JobOrder order, UUID actor, @Nullable UUID account) {
    if (!access.responsible(order) && !actor.equals(account)) {
      throw new AccessDeniedException("Caller may not move this holder's money on this order.");
    }
  }

  private void checkSamePerson(Parties parties, ReassignJobOrderMoneyDto dto) {
    if (parties.accounts().sameAccount()) {
      if (parties.destination().getId() == null
          && holderChoices
              .activeUser(Objects.requireNonNull(parties.source().getUserId()))
              .isEmpty()) {
        throw new BadRequestException("orders.money.holder.accountInactive");
      }
    } else if (!Boolean.TRUE.equals(dto.confirmSamePerson()) || !parties.namedPair()) {
      throw new BadRequestException("orders.money.reassignment.samePerson");
    }
  }

  private static void checkVersions(Parties parties, ReassignJobOrderMoneyDto dto) {
    OptimisticLock.check(
        parties.source().getRevision(),
        dto.sourceVersion(),
        JobOrderMoneyHolder.class,
        parties.source().getId());
    OptimisticLock.check(
        parties.destination().getRevision(),
        dto.destinationVersion(),
        JobOrderMoneyHolder.class,
        parties.destination().getId());
  }

  private Balances balances(
      JobOrder source, JobOrder destination, Parties parties, BigDecimal amount) {
    return new Balances(
        ledger.read(source.getId()).balance(parties.source().getId()).subtract(amount),
        ledger.read(destination.getId()).balance(parties.destination().getId()).add(amount));
  }

  private static boolean acceptsReassignment(JobOrder source, JobOrder destination) {
    return source.getStatus() != JobOrderStatus.REJECTED && destination.getStatus().isActive();
  }

  private MoneyPairEdit<Optional<JobOrderMoneyOutcome>> refuse(
      JobOrder source, JobOrder destination, UUID operationId) {
    operations.reserveRefusal(source.getId(), operationId);
    operations.reserveRefusal(destination.getId(), operationId);
    return unchanged(source, destination, Optional.empty());
  }

  private static MoneyPairEdit<Optional<JobOrderMoneyOutcome>> unchanged(
      JobOrder source, JobOrder destination, Optional<JobOrderMoneyOutcome> outcome) {
    return new MoneyPairEdit<>(
        outcome, JobOrderEdit.unchanged(source), JobOrderEdit.unchanged(destination));
  }

  private static BigDecimal amount(UUID orderId, ReassignJobOrderMoneyDto dto) {
    if (orderId.equals(dto.destinationOrderId())) {
      throw new BadRequestException("orders.money.reassignment.order");
    }
    if (!Boolean.FALSE.equals(dto.bankHandling())) {
      throw new BadRequestException("orders.money.reassignment.bankUnsupported");
    }
    return WholeAuec.positive(dto.amount());
  }

  private static BusinessConflictException termsMismatch() {
    return new BusinessConflictException("orders.money.operation.termsMismatch");
  }

  private static boolean namedAccountless(JobOrderMoneyHolder holder) {
    String name = holder.getAccountlessName();
    return holder.getUserId() == null && name != null && !HandleAnonymisation.isAnonymised(name);
  }

  /** Both current holder records within their respective order. */
  private record Parties(JobOrderMoneyHolder source, JobOrderMoneyHolder destination) {

    Accounts accounts() {
      return new Accounts(source.getUserId(), destination.getUserId());
    }

    boolean retired() {
      return source.getMergedIntoId() != null || destination.getMergedIntoId() != null;
    }

    boolean namedPair() {
      return namedAccountless(source) && namedAccountless(destination);
    }
  }

  /** Account keys established before either order row can be locked. */
  private record Accounts(@Nullable UUID source, @Nullable UUID destination) {

    boolean sameAccount() {
      return source != null && source.equals(destination);
    }
  }

  /** Exact reported balances after the proposed assignment. */
  private record Balances(BigDecimal sourceAfter, BigDecimal destinationAfter) {}

  /** Submitted operation and its pre-lock account plan. */
  private record Attempt(
      UUID id, UUID actor, Accounts accounts, ReassignJobOrderMoneyDto dto, BigDecimal amount) {}
}
