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
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffRights;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads one booking request for its own page, the target of the exchange queue's {@code link}
 * (REQ-BANK-068): the requester, the bank staff who see its account and its approvers read it;
 * anyone else gets the same {@code 404} as for an unknown request.
 */
@Service
@RequiredArgsConstructor
public class BankBookingRequestViewService {

  /** The message of the {@code 404} for an unknown request and for one the actor may not see. */
  private static final String NOT_FOUND = "Booking request not found";

  private final BankAccessRules policy;
  private final BankBookingRequestRepository requestRepository;
  private final BankRequestClientNames clientNames;

  /**
   * Reads a request the actor may see; a requester who is neither staff nor approver gets it
   * without the bank-internal staff note (REQ-BANK-054).
   *
   * @param actor the reading member
   * @param requestId the request
   * @return the request with the actor's roles on it
   * @throws NotFoundException when the request is unknown or the actor may not see it
   */
  @NotNull
  @Transactional(readOnly = true)
  public BankBookingRequestViewDto view(@NotNull BankActor actor, @NotNull UUID requestId) {
    BankBookingRequest request = Entities.require(requestRepository.findById(requestId), NOT_FOUND);
    BankAccessRules.ForeignRequestScope scope = policy.foreignRequestScope(actor);
    BankStaffRights rights = scope.rights();
    UUID accountId = request.getAccount().getId();
    boolean own = policy.ownsRequest(actor, request);
    boolean staff = rights.canSee(accountId);
    boolean approver =
        !rights.boundByFourEyes(request.getRequestedBy())
            && scope.candidateAccountIds().contains(accountId)
            && scope.shows(request.getRequiredApprover(), accountId);
    if (!own && !staff && !approver) {
      throw new NotFoundException(NOT_FOUND);
    }
    BankBookingRequestDto dto =
        BankBookingRequestDtos.of(
            request,
            clientNames.nameOf(request),
            staff ? BankBookingRequestDtos.callerMayConfirm(request, rights) : null);
    return new BankBookingRequestViewDto(
        staff || approver ? dto : dto.withoutStaffNote(), own, staff, approver);
  }
}
