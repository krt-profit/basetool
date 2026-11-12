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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort.AccountRef;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Offers minimal Bank choices for the current Order money actions. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class JobOrderMoneyBankAccountQueries {

  private final JobOrderMoneyQueries queries;
  private final JobOrderMoneyAccess access;
  private final JobOrderLinkedMoneyCommands linked;
  private final JobOrderMoneyAccountHoldingRepository holdings;
  private final JobOrderMoneyHolderRepository playerHoldings;
  private final JobOrderMoneyRecipients recipients;
  private final JobOrderMoneyRecipientRepository designations;
  private final JobOrderMoneyLedger ledger;

  /**
   * Returns candidates for a readable Order and the caller's current action grants.
   *
   * @param orderId the owning Order
   * @return the eligible candidate references
   */
  public JobOrderMoneyBankAccounts candidates(UUID orderId) {
    var order = queries.requireReadableMoneyOrder(orderId);
    UUID actor = access.requireActiveActor();
    boolean active = order.getStatus().isActive();
    boolean unitWriter = access.unitWriter(order);
    boolean maySettle = unitWriter || ownsSettlement(orderId, actor);
    if (queries.responsibilityInvalid(order)
        || order.getStatus() == JobOrderStatus.REJECTED
        || (!active && !maySettle)) {
      throw new AccessDeniedException("Caller may not select Bank accounts for this order.");
    }
    var choices =
        linked
            .port()
            .accountOptions(
                order.getResponsibleOrgUnit().getId(),
                order.getRequestingOrgUnit().getId(),
                active || maySettle,
                active && unitWriter,
                maySettle);
    Map<UUID, Long> revisions =
        holdings.findAllByOrderIdOrderById(orderId).stream()
            .collect(
                Collectors.toMap(
                    JobOrderMoneyAccountHolding::getBankAccountId,
                    JobOrderMoneyAccountHolding::getRevision));
    return new JobOrderMoneyBankAccounts(
        active ? refs(choices.collection(), revisions) : List.of(),
        maySettle ? refs(choices.collection(), Map.of()) : List.of(),
        refs(choices.designation(), Map.of()),
        refs(choices.withdrawal(), revisions),
        active ? refs(choices.transfer(), Map.of()) : List.of(),
        choices.custodians(),
        unitWriter ? personalReturnAccounts(orderId, choices.withdrawal()) : Map.of());
  }

  private Map<UUID, UUID> personalReturnAccounts(UUID orderId, List<AccountRef> withdrawal) {
    if (withdrawal.isEmpty()) {
      return Map.of();
    }
    var admitted = withdrawal.stream().map(AccountRef::id).collect(Collectors.toUnmodifiableSet());
    var eligible =
        designations.findAllByOrderIdAndBankAccountIdInAndCorrectedIntoIdIsNull(orderId, admitted);
    if (eligible.isEmpty()) {
      return Map.of();
    }
    var booked = ledger.read(orderId);
    return eligible.stream()
        .filter(designation ->
            booked
                    .availableForPersonalReturn(Objects.requireNonNull(designation.getId()), null)
                    .signum()
                > 0)
        .collect(
            Collectors.toUnmodifiableMap(
                designation -> Objects.requireNonNull(designation.getId()),
                designation -> Objects.requireNonNull(designation.getBankAccountId())));
  }

  private boolean ownsSettlement(UUID orderId, UUID actor) {
    return playerHoldings
            .findByOrderIdAndUserId(orderId, actor)
            .filter(holder -> holder.getMergedIntoId() == null)
            .isPresent()
        || recipients.views(orderId).stream()
            .anyMatch(recipient -> actor.equals(recipient.userId()));
  }

  private static List<JobOrderMoneyBankAccounts.Candidate> refs(
      List<AccountRef> refs, Map<UUID, Long> revisions) {
    return refs.stream().map(ref -> candidate(ref, revisions.getOrDefault(ref.id(), 0L))).toList();
  }

  private static JobOrderMoneyBankAccounts.Candidate candidate(AccountRef ref, long version) {
    return new JobOrderMoneyBankAccounts.Candidate(ref.id(), ref.name(), version);
  }
}
