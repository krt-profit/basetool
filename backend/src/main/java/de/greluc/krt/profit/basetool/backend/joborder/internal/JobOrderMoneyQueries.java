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
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderType;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reconstructs current order money and authorizes durable outcome reads. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class JobOrderMoneyQueries {

  private final JobOrderRepository orders;
  private final JobOrderAccessPolicy access;
  private final JobOrderMoneyTargetRepository targets;
  private final JobOrderMoneyHolderRepository holders;
  private final JobOrderMoneyOperationRepository operations;
  private final JobOrderMoneyHolders holderChoices;
  private final AuthHelperService auth;
  private final JobOrderMoneyAccess moneyAccess;
  private final JobOrderMoneyLedger ledger;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyTargetChangeRepository targetChanges;

  /**
   * Reconstructs current reported amounts from effective monetary facts.
   *
   * @param orderId the aUEC order
   * @return the current money view
   */
  public JobOrderMoneyView getMoney(UUID orderId) {
    JobOrder order = requireReadableMoneyOrder(orderId);
    JobOrderMoneyTarget target = targets.findById(orderId).orElseThrow();
    var booked = ledger.read(orderId);
    List<JobOrderMoneyHolder> identities =
        booked.identities().values().stream()
            .sorted(Comparator.comparing(JobOrderMoneyHolder::getId))
            .toList();
    Map<UUID, String> names = peopleNames(identities, booked.facts());
    List<JobOrderMoneyRecipientView> designations = recipients.views(orderId);
    Map<UUID, JobOrderMoneyRecipientView> recipientsById =
        designations.stream().collect(Collectors.toMap(JobOrderMoneyRecipientView::id, r -> r));
    List<JobOrderMoneyHolderView> rows =
        identities.stream()
            .filter(holder -> holder.getMergedIntoId() == null)
            .map(holder ->
                new JobOrderMoneyHolderView(
                    Objects.requireNonNull(holder.getId()),
                    holder.getUserId(),
                    label(holder, names),
                    booked.balance(holder.getId()).toPlainString(),
                    holder.getRevision()))
            .toList();
    List<JobOrderMoneyOutcome> history =
        booked.facts().stream()
            .map(fact ->
                JobOrderMoneyOutcome.of(
                    fact,
                    label(booked.holder(fact.getHolderId()), names),
                    fact.getActorId() == null ? null : names.get(fact.getActorId()),
                    destinationName(fact, booked, names, recipientsById)))
            .toList();
    boolean validResponsibility = !responsibilityInvalid(order);
    boolean activeAccount = auth.currentUserId().flatMap(holderChoices::activeUser).isPresent();
    boolean self = validResponsibility && activeAccount && order.getStatus().isActive();
    boolean unitWriter = validResponsibility && activeAccount && moneyAccess.unitWriter(order);
    BigDecimal outstanding = target.getAmount().subtract(booked.handedOver());
    return new JobOrderMoneyView(
        target.getAmount().toPlainString(),
        Objects.requireNonNull(target.getVersion()),
        booked.provided().toPlainString(),
        booked.holdings().toPlainString(),
        booked.handedOver().toPlainString(),
        outstanding.max(BigDecimal.ZERO).toPlainString(),
        rows,
        history,
        self,
        self && moneyAccess.responsible(order),
        validResponsibility && auth.isAdmin() && booked.facts().isEmpty(),
        booked.fees().toPlainString(),
        outstanding.negate().max(BigDecimal.ZERO).toPlainString(),
        designations,
        self && unitWriter,
        unitWriter,
        unitWriter,
        order.getMoneyInformationVersion(),
        self && moneyAccess.responsible(order),
        self && moneyAccess.requester(order),
        targetHistory(orderId));
  }

  private List<JobOrderMoneyTargetChangeView> targetHistory(UUID orderId) {
    List<JobOrderMoneyTargetChange> changes =
        targetChanges.findAllByOrderIdOrderByRecordedAtAscIdAsc(orderId);
    Map<UUID, String> names =
        holderChoices.namesOf(
            changes.stream()
                .map(JobOrderMoneyTargetChange::getActorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
    return changes.stream()
        .map(change ->
            new JobOrderMoneyTargetChangeView(
                Objects.requireNonNull(change.getId()),
                change.getPreviousTarget().toPlainString(),
                change.getTarget().toPlainString(),
                change.getRecordedAt(),
                change.getActorId() == null ? null : names.get(change.getActorId())))
        .toList();
  }

  private static @Nullable String destinationName(
      JobOrderMoneyOperation operation,
      JobOrderMoneyLedger.Ledger booked,
      Map<UUID, String> names,
      Map<UUID, JobOrderMoneyRecipientView> designations) {
    UUID destinationId = operation.getDestinationHolderId();
    if (destinationId != null) {
      return label(booked.holder(destinationId), names);
    }
    UUID recipientId = operation.getRecipientId();
    return recipientId == null
        ? null
        : Objects.requireNonNull(designations.get(recipientId)).name();
  }

  private Map<UUID, String> peopleNames(
      List<JobOrderMoneyHolder> identities, List<JobOrderMoneyOperation> facts) {
    var ids = new HashSet<UUID>();
    identities.forEach(holder -> {
      if (holder.getUserId() != null) {
        ids.add(holder.getUserId());
      }
    });
    facts.forEach(operation -> {
      if (operation.getActorId() != null) {
        ids.add(operation.getActorId());
      }
    });
    return holderChoices.namesOf(ids);
  }

  private static @Nullable String label(JobOrderMoneyHolder holder, Map<UUID, String> names) {
    return holder.getUserId() == null ? holder.getAccountlessName() : names.get(holder.getUserId());
  }

  /**
   * Recovers a decided money operation without performing a write.
   *
   * @param orderId the aUEC order whose current read access governs lookup
   * @param operationId the caller's logical operation identity
   * @return the accepted outcome, or empty for a durable refusal with no monetary effect
   * @throws NotFoundException if no outcome is known
   */
  public Optional<JobOrderMoneyOutcome> getOutcome(UUID orderId, UUID operationId) {
    requireReadableMoneyOrder(orderId);
    var accepted =
        operations
            .findByOrderIdAndOperationId(orderId, operationId)
            .map(operation ->
                projectOutcome(operation, holders.findById(operation.getHolderId()).orElseThrow()));
    if (accepted.isEmpty() && !operations.isRefused(orderId, operationId)) {
      throw new NotFoundException("orders.money.operation.unknown");
    }
    return accepted;
  }

  /**
   * Searches active accounts only for a current responsible writer who may record a provision.
   *
   * @param orderId the order owning the picker
   * @param query the optional name fragment
   * @return the bounded list of eligible account references
   */
  public List<JobOrderMoneyHolderCandidate> holderCandidates(UUID orderId, @Nullable String query) {
    JobOrder order = requireReadableMoneyOrder(orderId);
    if (access.responsibilityInvalid(order)
        || !order.getStatus().isActive()
        || !access.canTransferResponsibility(order)
        || auth.currentUserId().flatMap(holderChoices::activeUser).isEmpty()) {
      throw new AccessDeniedException("Caller may not choose a holder for this order.");
    }
    return holderChoices.candidates(query);
  }

  JobOrderMoneyOutcome projectOutcome(
      JobOrderMoneyOperation operation, JobOrderMoneyHolder holder) {
    UUID aliasTarget = holder.getMergedIntoId();
    JobOrderMoneyHolder current =
        aliasTarget == null ? holder : holders.findById(aliasTarget).orElseThrow();
    return JobOrderMoneyOutcome.of(
        operation,
        holderChoices.nameOf(current),
        holderChoices.nameOf(operation.getActorId()),
        movementDestinationName(operation));
  }

  private @Nullable String movementDestinationName(JobOrderMoneyOperation operation) {
    UUID destinationId = operation.getDestinationHolderId();
    if (destinationId != null) {
      JobOrderMoneyHolder destination = holders.findById(destinationId).orElseThrow();
      UUID canonicalId = destination.getMergedIntoId();
      return holderChoices.nameOf(
          canonicalId == null ? destination : holders.findById(canonicalId).orElseThrow());
    }
    UUID recipientId = operation.getRecipientId();
    return recipientId == null
        ? null
        : recipients.party(recipients.require(operation.getOrderId(), recipientId)).name();
  }

  /**
   * Searches eligible accounts for a caller authorized to designate a requester recipient.
   *
   * @param orderId the owning order
   * @param query optional name fragment
   * @return eligible recipient accounts
   */
  public List<JobOrderMoneyHolderCandidate> recipientCandidates(
      UUID orderId, @Nullable String query) {
    JobOrder order = requireReadableMoneyOrder(orderId);
    moneyAccess.requireActiveActor();
    moneyAccess.requireUnitWriter(order);
    if (responsibilityInvalid(order) || !order.getStatus().isActive()) {
      throw new AccessDeniedException("Caller may not designate a recipient for this order.");
    }
    return holderChoices.candidates(query);
  }

  boolean responsibilityInvalid(JobOrder order) {
    return access.responsibilityInvalid(order);
  }

  JobOrder requireReadableMoneyOrder(UUID orderId) {
    JobOrder order =
        Entities.require(orders.findById(orderId), () -> "JobOrder not found: " + orderId);
    if (!access.canSeeJobOrder(order)) {
      throw new AccessDeniedException("Caller may not read this order.");
    }
    requireMoneyKind(order);
    return order;
  }

  static void requireMoneyKind(JobOrder order) {
    if (order.getType() != JobOrderType.AUEC) {
      throw new NotFoundException("orders.money.kind.required");
    }
  }
}
