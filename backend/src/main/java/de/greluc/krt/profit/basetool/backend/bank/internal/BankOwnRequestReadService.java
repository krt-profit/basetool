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

import de.greluc.krt.profit.basetool.backend.bank.api.BankOwnRequestReads;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves {@link BankOwnRequestReads} from the requester's own booking requests. */
@Service
@RequiredArgsConstructor
public class BankOwnRequestReadService implements BankOwnRequestReads {

  private final BankBookingRequestRepository requestRepository;
  private final BankExchangeProperties properties;

  @Override
  @NotNull
  @Transactional(readOnly = true)
  public OwnRequestPage ownRequests(@NotNull UUID requester, @Nullable UUID afterId, int limit) {
    PageRequest page = PageRequest.of(0, limit + 1);
    List<BankBookingRequest> rows =
        afterId == null
            ? requestRepository.findByRequestedByOrderByIdAsc(requester, page)
            : requestRepository.findByRequestedByAndIdGreaterThanOrderByIdAsc(
                requester, afterId, page);
    boolean more = rows.size() > limit;
    return new OwnRequestPage(
        (more ? rows.subList(0, limit) : rows)
            .stream().map(BankOwnRequestReadService::toOwnRequest).toList(),
        more);
  }

  @Override
  @NotNull
  @Unmodifiable
  @Transactional(readOnly = true)
  public List<OwnRequest> ownRequestsByIds(@NotNull UUID requester, @NotNull Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return requestRepository.findByRequestedByAndIdIn(requester, ids).stream()
        .map(BankOwnRequestReadService::toOwnRequest)
        .toList();
  }

  @Override
  public int openRequestCap() {
    return properties.openRequestCap();
  }

  /**
   * Shapes one request without its staff, counterparty and purpose fields.
   *
   * @param request the request
   * @return the member's view of it
   */
  @NotNull
  private static OwnRequest toOwnRequest(@NotNull BankBookingRequest request) {
    BankAccount account = request.getAccount();
    OrgUnit owner = account.getOrgUnit();
    BankAccount target = request.getTargetAccount();
    return new OwnRequest(
        request.getId(),
        request.getVersion(),
        request.getStatus().name(),
        request.getCreatedAt(),
        request.getDecidedAt(),
        request.getType(),
        request.getAmount(),
        request.getNote(),
        request.getJustification(),
        new Account(
            account.getId(),
            account.getAccountNo(),
            account.getName(),
            owner == null ? null : owner.getShorthand()),
        target == null ? null : new TargetAccount(target.getId(), target.getAccountNo()),
        request.isRequiresOwnerApproval(),
        request.getRequiredApprover(),
        request.isOwnerApprovalGranted(),
        request.getRejectReason(),
        request.getClientId(),
        request.isSplitEnabled(),
        request.getSplitPercent());
  }
}
