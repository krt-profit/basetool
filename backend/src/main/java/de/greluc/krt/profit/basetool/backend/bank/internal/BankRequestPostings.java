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

import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Posts the ledger movements of an ordinary Bank request. */
@Service
@RequiredArgsConstructor
class BankRequestPostings {
  private final BankLedgerService bankLedgerService;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  /**
   * Books the ordinary request with the staff-selected custodians.
   *
   * @param request the locked request
   * @param holderId the selected holder
   * @param destinationHolderId the selected receiving holder for a transfer
   * @param staffNote the private Bank note
   * @return the transaction that was posted
   */
  @NotNull
  BankTransactionDto book(
      @NotNull BankBookingRequest request,
      @NotNull UUID holderId,
      @Nullable UUID destinationHolderId,
      @Nullable String staffNote) {
    UUID accountId = Objects.requireNonNull(request.accountId());

    UUID namedCounterparty = request.getCounterpartyUserId();
    UUID requesterId = namedCounterparty != null ? namedCounterparty : request.getRequestedBy();
    UUID counterpartyOrgUnitId = confirmCounterpartyOrgUnitId(request, requesterId);

    return switch (request.getType()) {
      case DEPOSIT ->
          bankLedgerService.bookDeposit(
              new BankDepositRequest(
                  accountId,
                  holderId,
                  request.getAmount(),
                  request.getNote(),
                  staffNote,
                  request.isSplitEnabled(),
                  request.getSplitPercent(),
                  requesterId,
                  counterpartyOrgUnitId,
                  null));
      case WITHDRAWAL ->
          bankLedgerService.bookWithdrawal(
              new BankWithdrawalRequest(
                  accountId,
                  holderId,
                  request.getAmount(),
                  request.getNote(),
                  request.getJustification(),
                  staffNote,
                  requesterId,
                  counterpartyOrgUnitId,
                  false,
                  null));
      case TRANSFER -> {
        BankAccount target = request.getTargetAccount();
        if (target == null || destinationHolderId == null) {
          throw new NotFoundException(
              "A transfer confirmation requires the destination account and holder");
        }
        yield bankLedgerService.bookTransfer(
            new BankTransferRequest(
                accountId,
                holderId,
                target.getId(),
                destinationHolderId,
                request.getAmount(),
                request.getNote(),
                request.getJustification(),
                staffNote,
                false),
            true);
      }
      case HOLDER_TRANSFER, AUTHORISATION ->
          throw new IllegalStateException("A linked request requires a prepared outcome handler");
    };
  }

  /**
   * Resolves the org unit to stamp on a confirmed booking for the counterparty (REQ-BANK-055): the
   * stored choice while it is still one of the counterparty's memberships, else their current
   * primary unit, else none.
   *
   * @param request the request being confirmed
   * @param counterpartyId the resolved counterparty (the named Empf&auml;nger, else the requester),
   *     or {@code null} when the request carries neither
   * @return the org unit to record, or {@code null}
   */
  @Nullable
  private UUID confirmCounterpartyOrgUnitId(
      @NotNull BankBookingRequest request, @Nullable UUID counterpartyId) {
    if (counterpartyId == null) {
      return null;
    }
    UUID stored = request.getCounterpartyOrgUnitId();
    if (stored != null
        && orgUnitMembershipQueryService.listDirectMembershipOptions(counterpartyId).stream()
            .anyMatch(option -> option.orgUnitId().equals(stored))) {
      return stored;
    }
    return orgUnitMembershipQueryService
        .findPrimaryDirectMembershipOrgUnitId(counterpartyId)
        .orElse(null);
  }
}
