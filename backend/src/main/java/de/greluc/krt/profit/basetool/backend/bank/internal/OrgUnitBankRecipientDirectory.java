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

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.notification.api.AccountRecipientDirectory;
import de.greluc.krt.profit.basetool.backend.notification.api.RoleRecipientDirectory;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank module's {@link AccountRecipientDirectory}: grant holders from {@code
 * bank_account_grant}, responsible holders through {@link OrgUnitBankResponsibilityService}, and
 * the required approvers of a booking request from its {@code required_approver}.
 *
 * <p>On the org-unit side of the bank, because the responsible holders and the OL follow org-unit
 * membership (ADR-0020).
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBankRecipientDirectory implements AccountRecipientDirectory {

  private final BankAccountGrantRepository bankAccountGrantRepository;
  private final BankAccountRepository bankAccountRepository;
  private final BankBookingRequestRepository bankBookingRequestRepository;
  private final OrgUnitBankResponsibilityService orgUnitBankResponsibilityService;
  private final OrgUnitMembershipRepository orgUnitMembershipRepository;
  private final RoleRecipientDirectory roleRecipientDirectory;

  /**
   * The bank employees holding a {@code bank_account_grant} on an account (REQ-BANK-026).
   *
   * @param accountId the bank account
   * @return the grantees' user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  public Set<UUID> grantHoldersOf(@NotNull UUID accountId) {
    Set<UUID> holders = new HashSet<>();
    for (BankAccountGrant grant : bankAccountGrantRepository.findByAccountId(accountId)) {
      holders.add(grant.getId().getUserId());
    }
    return holders;
  }

  /**
   * The responsible holders of an account (REQ-BANK-034).
   *
   * @param accountId the bank account
   * @return their user subs; never {@code null}, empty for a Sonderkonto or an unlinked account
   */
  @Override
  @NotNull
  public Set<UUID> responsibleHoldersOf(@NotNull UUID accountId) {
    return orgUnitBankResponsibilityService.resolveResponsibleHolderUserIds(accountId);
  }

  /**
   * The responsible holders told of every request on an account; none on the KRT account, whose
   * requests reach the OL only as required approver above {@code T2} (REQ-NOTIF-033).
   *
   * @param accountId the bank account
   * @return their user subs; never {@code null}, empty on the KRT account or a missing account
   */
  @Override
  @NotNull
  @Transactional(readOnly = true)
  public Set<UUID> requestResponsibleHoldersOf(@NotNull UUID accountId) {
    return bankAccountRepository
        .findById(accountId)
        .filter(account -> account.getType() != BankAccountType.CARTEL)
        .map(account -> responsibleHoldersOf(account.getId()))
        .orElse(Set.of());
  }

  /**
   * The members whose approval a pending, over-limit, not yet approved request waits for, by its
   * {@code required_approver} (REQ-NOTIF-033, REQ-BANK-041/-047): the account's responsible
   * holders, the {@code BANK_MANAGEMENT} role holders or every {@code OL_MEMBER}. A request naming
   * no approver class falls to the responsible holders, as its approval does. The requester is left
   * out, because they may not approve their own request (REQ-BANK-062).
   *
   * @param requestId the booking request
   * @return their user subs; never {@code null}, empty when no approval is outstanding
   */
  @Override
  @NotNull
  @Transactional(readOnly = true)
  public Set<UUID> requiredApproversOf(@NotNull UUID requestId) {
    BankBookingRequest request = bankBookingRequestRepository.findById(requestId).orElse(null);
    if (request == null || !awaitsApproval(request)) {
      return Set.of();
    }
    Set<UUID> approvers = new HashSet<>(approversOf(request));
    UUID requester = request.getRequestedBy();
    if (requester != null) {
      approvers.remove(requester);
    }
    return approvers;
  }

  /**
   * Whether a request still waits for an approval: pending, flagged and not yet approved.
   *
   * @param request the request
   * @return {@code true} iff an approval is outstanding
   */
  private static boolean awaitsApproval(@NotNull BankBookingRequest request) {
    return request.getStatus() == BankBookingRequestStatus.PENDING
        && request.isRequiresOwnerApproval()
        && !request.isOwnerApprovalGranted();
  }

  /**
   * Resolves the request's approver class to its members.
   *
   * @param request the flagged request
   * @return the members of the approver class; never {@code null}, possibly empty
   */
  @NotNull
  private Set<UUID> approversOf(@NotNull BankBookingRequest request) {
    BankRequestApprover approver = request.getRequiredApprover();
    UUID accountId = request.getAccount().getId();
    return switch (approver) {
      case RESPONSIBLE_HOLDER -> responsibleHoldersOf(accountId);
      case BANK_MANAGEMENT -> roleRecipientDirectory.holdersOfRole(Roles.BANK_MANAGEMENT);
      case ORGANISATIONSLEITUNG ->
          orgUnitMembershipRepository.findUserIdsByRole(MembershipRole.OL_MEMBER);
      case null -> responsibleHoldersOf(accountId);
    };
  }
}
