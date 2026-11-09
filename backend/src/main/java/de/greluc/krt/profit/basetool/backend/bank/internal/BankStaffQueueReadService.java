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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffQueueReads;
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffRights;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves {@link BankStaffQueueReads} from the pending requests through the policy ({@link
 * BankAccessRules}): {@code open} is the web staff queue's set ({@link
 * BankBookingRequestService#listQueue}), {@code toApprove} the requests whose "approval needed"
 * notice goes to the actor ({@link OrgUnitBankRecipientDirectory#requiredApproversOf}).
 */
@Service
@RequiredArgsConstructor
public class BankStaffQueueReadService implements BankStaffQueueReads {

  /** Oldest first; requests of one instant by their id's text, which a cursor can carry. */
  private static final Comparator<QueueKey> ORDER =
      Comparator.comparing(QueueKey::createdAt).thenComparing(key -> key.requestId().toString());

  private final BankAccessRules policy;
  private final BankBookingRequestRepository requestRepository;
  private final BankPostingRepository postingRepository;

  @Override
  @NotNull
  @Transactional(readOnly = true)
  public Optional<Snapshot> snapshot(
      @NotNull BankActor actor,
      @Nullable QueueKey openAfter,
      @Nullable QueueKey toApproveAfter,
      int limit) {
    BankAccessRules.ForeignRequestScope scope = policy.foreignRequestScope(actor);
    BankStaffRights rights = scope.rights();
    List<BankBookingRequest> pending =
        requestRepository.findByStatus(BankBookingRequestStatus.PENDING).stream()
            .sorted(Comparator.comparing(BankStaffQueueReadService::key, ORDER))
            .toList();
    List<BankBookingRequest> open =
        pending.stream().filter(request -> inStaffQueue(request, rights)).toList();
    List<BankBookingRequest> toApprove =
        pending.stream().filter(request -> awaitsApprovalBy(request, scope)).toList();
    if (!rights.staff() && rights.grantedAccountIds().isEmpty() && toApprove.isEmpty()) {
      return Optional.empty();
    }
    List<BankBookingRequest> openPage = after(open, openAfter, limit + 1);
    List<BankBookingRequest> approvePage = after(toApprove, toApproveAfter, limit + 1);
    Map<UUID, BigDecimal> balances =
        balances(
            Stream.concat(trim(openPage, limit).stream(), trim(approvePage, limit).stream()),
            rights);
    return Optional.of(
        new Snapshot(
            section(openPage, limit, rights, balances),
            section(approvePage, limit, rights, balances),
            Stream.concat(open.stream(), toApprove.stream())
                .map(BankBookingRequest::getCreatedAt)
                .min(Comparator.naturalOrder())
                .orElse(null)));
  }

  /**
   * Whether the web staff queue shows the request to the actor: every request for the Bankleitung,
   * the requests on a granted account for an employee (REQ-BANK-023).
   *
   * @param request the pending request
   * @param rights the actor's staff rights
   * @return {@code true} iff the request belongs to {@code open}
   */
  private static boolean inStaffQueue(
      @NotNull BankBookingRequest request, @NotNull BankStaffRights rights) {
    return rights.canSee(request.getAccount().getId());
  }

  /**
   * Whether the request waits for the actor's approval: flagged, not yet approved, not raised by
   * the actor and routed to an approver class the actor belongs to (REQ-BANK-041, REQ-NOTIF-033).
   *
   * @param request the pending request
   * @param scope the actor's foreign-request scope
   * @return {@code true} iff the request belongs to {@code toApprove}
   */
  private static boolean awaitsApprovalBy(
      @NotNull BankBookingRequest request, @NotNull BankAccessRules.ForeignRequestScope scope) {
    return request.isRequiresOwnerApproval()
        && !request.isOwnerApprovalGranted()
        && !scope.rights().boundByFourEyes(request.getRequestedBy())
        && scope.candidateAccountIds().contains(request.getAccount().getId())
        && scope.shows(request.getRequiredApprover(), request.getAccount().getId());
  }

  /**
   * The requests of a section after a key, at most {@code count} of them.
   *
   * @param section the whole section, oldest first
   * @param after the last item delivered, or {@code null}
   * @param count the most requests to return
   * @return the following requests
   */
  @NotNull
  private static List<BankBookingRequest> after(
      @NotNull List<BankBookingRequest> section, @Nullable QueueKey after, int count) {
    Predicate<BankBookingRequest> follows =
        after == null ? _ -> true : request -> ORDER.compare(key(request), after) > 0;
    return section.stream().filter(follows).limit(count).toList();
  }

  /**
   * The first {@code limit} requests of a page read with one extra.
   *
   * @param page the page with up to {@code limit + 1} requests
   * @param limit the page size
   * @return the delivered requests
   */
  @NotNull
  private static List<BankBookingRequest> trim(@NotNull List<BankBookingRequest> page, int limit) {
    return page.size() > limit ? page.subList(0, limit) : page;
  }

  /**
   * Shapes one section page.
   *
   * @param page the page with up to {@code limit + 1} requests
   * @param limit the page size
   * @param rights the actor's staff rights
   * @param balances the balances the actor may see, by account
   * @return the section
   */
  @NotNull
  private static Section section(
      @NotNull List<BankBookingRequest> page,
      int limit,
      @NotNull BankStaffRights rights,
      @NotNull Map<UUID, BigDecimal> balances) {
    return new Section(
        trim(page, limit).stream().map(request -> toItem(request, rights, balances)).toList(),
        page.size() > limit);
  }

  /**
   * Reads the current balances of the delivered requests' accounts the actor may see: every account
   * for the Bankleitung, a granted account for an employee.
   *
   * @param delivered the delivered requests
   * @param rights the actor's staff rights
   * @return the balances by account
   */
  @NotNull
  private Map<UUID, BigDecimal> balances(
      @NotNull Stream<BankBookingRequest> delivered, @NotNull BankStaffRights rights) {
    List<UUID> visible =
        delivered
            .map(request -> request.getAccount().getId())
            .filter(rights::canSee)
            .distinct()
            .toList();
    Map<UUID, BigDecimal> balances = new HashMap<>();
    if (!visible.isEmpty()) {
      for (BankAccountBalance row : postingRepository.accountBalances(visible)) {
        balances.put(row.accountId(), row.balance());
      }
    }
    visible.forEach(id -> balances.putIfAbsent(id, BigDecimal.ZERO));
    return balances;
  }

  /**
   * Projects one request.
   *
   * @param request the pending request
   * @param rights the actor's staff rights
   * @param balances the balances the actor may see, by account
   * @return the item
   */
  @NotNull
  private static Item toItem(
      @NotNull BankBookingRequest request,
      @NotNull BankStaffRights rights,
      @NotNull Map<UUID, BigDecimal> balances) {
    BankAccount account = request.getAccount();
    OrgUnit orgUnit = account.getOrgUnit();
    BankAccount target = request.getTargetAccount();
    return new Item(
        request.getId(),
        request.getType(),
        request.getAmount(),
        request.getCreatedAt(),
        new AccountRef(
            account.getId(),
            account.getAccountNo(),
            account.getName(),
            orgUnit == null ? null : orgUnit.getShorthand()),
        target == null ? null : new TargetRef(target.getId(), target.getAccountNo()),
        request.getRequestedBy(),
        request.getRequesterHandle(),
        request.getJustification(),
        request.getNote(),
        request.isRequiresOwnerApproval(),
        request.getRequiredApprover(),
        request.isOwnerApprovalGranted(),
        rights.canBook(request.getType(), account.getId())
            && !rights.boundByFourEyes(request.getRequestedBy()),
        balances.get(account.getId()));
  }

  /**
   * The position of a request in the oldest-first order.
   *
   * @param request the request
   * @return its key
   */
  @NotNull
  private static QueueKey key(@NotNull BankBookingRequest request) {
    return new QueueKey(request.getCreatedAt(), request.getId());
  }
}
