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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
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

  /**
   * Reconstructs current reported amounts from effective provisions.
   *
   * @param orderId the aUEC order
   * @return the current money view
   */
  public JobOrderMoneyView getMoney(UUID orderId) {
    JobOrder order = requireReadableMoneyOrder(orderId);
    JobOrderMoneyTarget target = targets.findById(orderId).orElseThrow();
    List<JobOrderMoneyOperation> facts =
        operations.findAllByOrderIdOrderByRecordedAtAscIdAsc(orderId);
    List<JobOrderMoneyHolder> identities = holders.findAllByOrderIdOrderById(orderId);
    Map<UUID, JobOrderMoneyHolder> byId =
        identities.stream()
            .collect(
                Collectors.toMap(
                    holder -> Objects.requireNonNull(holder.getId()), Function.identity()));
    Map<UUID, BigDecimal> booked = new HashMap<>();
    for (JobOrderMoneyOperation operation : facts) {
      booked.merge(
          canonicalId(byId, operation.getHolderId()), operation.getAmount(), BigDecimal::add);
    }
    BigDecimal total = booked.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    Map<UUID, String> names = peopleNames(identities, facts);
    List<JobOrderMoneyHolderView> rows =
        identities.stream()
            .filter(holder -> holder.getMergedIntoId() == null)
            .map(holder ->
                new JobOrderMoneyHolderView(
                    Objects.requireNonNull(holder.getId()),
                    holder.getUserId(),
                    label(holder, names),
                    booked.getOrDefault(holder.getId(), BigDecimal.ZERO).toPlainString(),
                    holder.getRevision()))
            .toList();
    List<JobOrderMoneyOutcome> history = history(byId, facts, names);
    boolean validResponsibility = !access.responsibilityInvalid(order);
    boolean self =
        validResponsibility
            && order.getStatus().isActive()
            && auth.currentUserId().flatMap(holderChoices::activeUser).isPresent();
    String requested = target.getAmount().toPlainString();
    return new JobOrderMoneyView(
        requested,
        Objects.requireNonNull(target.getVersion()),
        total.toPlainString(),
        total.toPlainString(),
        BigDecimal.ZERO.toPlainString(),
        requested,
        rows,
        history,
        self,
        self && access.canTransferResponsibility(order),
        validResponsibility && auth.isAdmin() && facts.isEmpty());
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

  private static List<JobOrderMoneyOutcome> history(
      Map<UUID, JobOrderMoneyHolder> byId,
      List<JobOrderMoneyOperation> facts,
      Map<UUID, String> names) {
    return facts.stream()
        .map(operation ->
            JobOrderMoneyOutcome.of(
                operation,
                label(
                    Objects.requireNonNull(byId.get(canonicalId(byId, operation.getHolderId()))),
                    names),
                operation.getActorId() == null ? null : names.get(operation.getActorId())))
        .toList();
  }

  private static UUID canonicalId(Map<UUID, JobOrderMoneyHolder> byId, UUID id) {
    UUID canonical = Objects.requireNonNull(byId.get(id)).getMergedIntoId();
    return canonical == null ? id : canonical;
  }

  private static @Nullable String label(JobOrderMoneyHolder holder, Map<UUID, String> names) {
    return holder.getUserId() == null ? holder.getAccountlessName() : names.get(holder.getUserId());
  }

  /**
   * Recovers an accepted provision without performing a write.
   *
   * @param orderId the aUEC order whose current read access governs lookup
   * @param operationId the caller's logical operation identity
   * @return the accepted outcome
   * @throws NotFoundException if no accepted outcome exists
   */
  public JobOrderMoneyOutcome getOutcome(UUID orderId, UUID operationId) {
    requireReadableMoneyOrder(orderId);
    return operations
        .findByOrderIdAndOperationId(orderId, operationId)
        .map(operation ->
            projectOutcome(operation, holders.findById(operation.getHolderId()).orElseThrow()))
        .orElseThrow(() -> new NotFoundException("orders.money.operation.unknown"));
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
        operation, holderChoices.nameOf(current), holderChoices.nameOf(operation.getActorId()));
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
