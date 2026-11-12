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
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderEditor.MoneyPairBasis;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderEditor.MoneyPairEdit;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyCorrectionPlans.Plan;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyCorrectionPlans.Terms;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyCorrectionPlans.UnavailableCorrection;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Coordinates both order-local corrections of a paired purpose movement. */
@Component
@RequiredArgsConstructor
class JobOrderMoneyPairedCorrection {

  private final JobOrderEditor editor;
  private final JobOrderMoneyAccess access;
  private final JobOrderMoneyIdentityLock identities;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyQueries queries;
  private final JobOrderMoneyCorrectionPlans plans;

  Optional<UUID> peerOrder(UUID orderId, Set<UUID> originalIds) {
    var peers =
        operations.findAllByOrderIdAndOperationIdIn(orderId, originalIds).stream()
            .filter(JobOrderMoneyPairedCorrection::purposeLeg)
            .map(JobOrderMoneyOperation::getPeerOrderId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (peers.size() > 1) {
      peers.forEach(queries::requireReadableMoneyOrder);
      throw new BadRequestException("orders.money.correction.onePeer");
    }
    return peers.stream().findFirst();
  }

  JobOrderMoneyCorrectionPreview preview(
      JobOrder order,
      UUID peerId,
      CorrectJobOrderMoneyDto dto,
      Map<UUID, Terms> terms,
      UUID actor) {
    JobOrder peer = queries.requireReadableMoneyOrder(peerId);
    if (queries.responsibilityInvalid(peer)) {
      throw new AccessDeniedException("The linked order's responsibility is ineligible.");
    }
    var linked = linkedTerms(order.getId(), peerId, terms);
    var primary = plans.prepare(order, dto, terms, actor, peerId, true);
    var other = plans.prepare(peer, dto, linked, actor, order.getId(), false);
    return primary.preview(other.holderVersions(), other.revised().holdings());
  }

  Optional<JobOrderMoneyOutcome> record(
      UUID orderId, UUID peerId, CorrectJobOrderMoneyDto dto, Map<UUID, Terms> terms, UUID actor) {
    queries.requireReadableMoneyOrder(peerId);
    Map<UUID, Terms> linked = linkedTerms(orderId, peerId, terms);
    AccountPlan accounts = accounts(orderId, peerId, terms.keySet(), linked.keySet());
    identities.lockAffectedAccounts(actor, accounts.all());
    return editor.editMoneyPair(
        orderId,
        peerId,
        order -> plans.needsQueue(order, dto.operationId(), terms),
        basis -> recordLocked(basis, new Attempt(dto, terms, linked, actor, accounts)));
  }

  private MoneyPairEdit<Optional<JobOrderMoneyOutcome>> recordLocked(
      MoneyPairBasis basis, Attempt attempt) {
    JobOrder order = basis.source();
    JobOrder peer = basis.destination();
    UUID orderId = order.getId();
    UUID peerId = peer.getId();
    access.requireActiveActor();
    UUID operationId = attempt.dto().operationId();
    var previous = operations.findByOrderIdAndOperationId(orderId, operationId);
    var peerPrevious = operations.findByOrderIdAndOperationId(peerId, operationId);
    if (previous.isPresent() || peerPrevious.isPresent()) {
      return replay(basis, attempt, previous.orElse(null), peerPrevious.orElse(null));
    }
    if (operations.isRefused(orderId, operationId) || operations.isRefused(peerId, operationId)) {
      return unchanged(basis);
    }

    Plan primary;
    Plan other;
    try {
      primary = plans.prepare(order, attempt.dto(), attempt.terms(), attempt.actor(), peerId, true);
      other = plans.prepare(peer, attempt.dto(), attempt.linked(), attempt.actor(), orderId, true);
    } catch (ObjectOptimisticLockingFailureException | UnavailableCorrection unavailable) {
      return refuse(basis, operationId);
    }
    if (!attempt
            .accounts()
            .equals(accounts(orderId, peerId, attempt.terms().keySet(), attempt.linked().keySet()))
        || (primary.lifecycleChanged() && !basis.queueLocked())) {
      return refuse(basis, operationId);
    }
    var posted = plans.post(order, operationId, attempt.actor(), primary, peerId);
    var peerPosted =
        plans.post(peer, operationId, attempt.actor(), other, orderId, primary.linkedAccounts());
    return new MoneyPairEdit<>(posted.result(), posted.edit(), peerPosted.edit());
  }

  private MoneyPairEdit<Optional<JobOrderMoneyOutcome>> replay(
      MoneyPairBasis basis,
      Attempt attempt,
      @Nullable JobOrderMoneyOperation primary,
      @Nullable JobOrderMoneyOperation linked) {
    if (primary == null || linked == null) {
      throw termsMismatch();
    }
    JobOrder order = basis.source();
    JobOrder peer = basis.destination();
    var replay = plans.replay(order, primary, attempt.terms(), attempt.actor(), peer.getId());
    var peerReplay = plans.replay(peer, linked, attempt.linked(), attempt.actor(), order.getId());
    return new MoneyPairEdit<>(replay.result(), replay.edit(), peerReplay.edit());
  }

  private Map<UUID, Terms> linkedTerms(UUID orderId, UUID peerId, Map<UUID, Terms> terms) {
    Map<UUID, JobOrderMoneyOperation> selected =
        operations.findAllByOrderIdAndOperationIdIn(orderId, terms.keySet()).stream()
            .filter(fact -> purposeLeg(fact) && peerId.equals(fact.getPeerOrderId()))
            .collect(Collectors.toMap(JobOrderMoneyOperation::getOperationId, Function.identity()));
    if (selected.isEmpty()) {
      throw termsMismatch();
    }
    var counterparts = operations.findAllByOrderIdAndOperationIdIn(peerId, selected.keySet());
    if (counterparts.size() != selected.size()) {
      throw termsMismatch();
    }
    var linked = new HashMap<UUID, Terms>();
    for (var counterpart : counterparts) {
      UUID id = counterpart.getOperationId();
      var original = Objects.requireNonNull(selected.get(id));
      var requested = Objects.requireNonNull(terms.get(id));
      if (!purposeLeg(counterpart)
          || counterpart.getKind() == original.getKind()
          || !orderId.equals(counterpart.getPeerOrderId())
          || original.getAmount().compareTo(counterpart.getAmount()) != 0) {
        throw termsMismatch();
      }
      linked.put(id, requested);
    }
    return Map.copyOf(linked);
  }

  private static boolean purposeLeg(JobOrderMoneyOperation fact) {
    return fact.getKind() == JobOrderMoneyKind.PURPOSE_INFLOW
        || fact.getKind() == JobOrderMoneyKind.PURPOSE_OUTFLOW;
  }

  private AccountPlan accounts(UUID orderId, UUID peerId, Set<UUID> primary, Set<UUID> linked) {
    return new AccountPlan(
        operations.accountsAffectedByCorrection(orderId, primary),
        operations.accountsAffectedByCorrection(peerId, linked));
  }

  private MoneyPairEdit<Optional<JobOrderMoneyOutcome>> refuse(MoneyPairBasis basis, UUID id) {
    operations.reserveRefusal(basis.source().getId(), id);
    operations.reserveRefusal(basis.destination().getId(), id);
    return unchanged(basis);
  }

  private static MoneyPairEdit<Optional<JobOrderMoneyOutcome>> unchanged(MoneyPairBasis basis) {
    return new MoneyPairEdit<>(
        Optional.empty(),
        JobOrderEdit.unchanged(basis.source()),
        JobOrderEdit.unchanged(basis.destination()));
  }

  private static BusinessConflictException termsMismatch() {
    return new BusinessConflictException("orders.money.operation.termsMismatch");
  }

  /** The complete affected identity scope for each order before taking either order lock. */
  private record AccountPlan(Set<UUID> primary, Set<UUID> linked) {

    Set<UUID> all() {
      return Stream.concat(primary.stream(), linked.stream())
          .collect(Collectors.toUnmodifiableSet());
    }
  }

  /** Submitted terms and the linked peer terms captured before locking. */
  private record Attempt(
      CorrectJobOrderMoneyDto dto,
      Map<UUID, Terms> terms,
      Map<UUID, Terms> linked,
      UUID actor,
      AccountPlan accounts) {}
}
