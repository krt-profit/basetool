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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.model.BankAccountGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountGrantId;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @PreAuthorize} helper bean for the bank surface (ADR-0011).
 *
 * <p>Decides only from the bank roles ({@code ADMIN > BANK_MANAGEMENT > BANK_EMPLOYEE}) and the
 * {@link BankAccountGrant} rows; org-unit membership and scope are never consulted (REQ-BANK-008).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class BankSecurityService {

  private final AuthHelperService authHelperService;
  private final BankAccountGrantRepository grantRepository;
  private final BankHolderRepository holderRepository;

  /**
   * Whether the current caller is bank staff at all — holds {@code ROLE_BANK_EMPLOYEE} directly or
   * reaches it via the hierarchy (management, admin). The coarse gate of every bank surface.
   *
   * @return {@code true} iff the caller reaches the Bank Employee role
   */
  public boolean isBankStaff() {
    return authHelperService.hasReachableRole(Roles.authority(Roles.BANK_EMPLOYEE));
  }

  /**
   * Whether the current caller is Bankleitung or above — sees and manages all accounts, holders and
   * grants (REQ-BANK-010). Admins reach this via the hierarchy.
   *
   * @return {@code true} iff the caller reaches the Bank Management role
   */
  public boolean isManagement() {
    return authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT));
  }

  /**
   * Checks whether the caller may see the account: bank management, or bank staff with any grant
   * row on it (REQ-BANK-009). An unknown account is denied.
   *
   * @param accountId the account to check; never {@code null}
   * @param authentication the current authentication; may be {@code null}
   * @return {@code true} iff the caller may read the account
   */
  public boolean canSee(@NotNull UUID accountId, Authentication authentication) {
    return hasCapability(accountId, authentication, g -> true);
  }

  /**
   * Checks whether the caller may see a holder's custody history (REQ-BANK-032): management may see
   * any holder, a bank employee only their own.
   *
   * @param holderId the holder whose history is requested; never {@code null}
   * @param authentication the current authentication; may be {@code null}
   * @return {@code true} iff the caller may read the holder's history
   */
  public boolean canSeeHolder(@NotNull UUID holderId, Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated() || !isBankStaff()) {
      return false;
    }
    if (isManagement()) {
      return true;
    }
    return authHelperService
        .currentUserId()
        .flatMap(
            uid ->
                holderRepository
                    .findById(holderId)
                    .map(
                        holder -> holder.getUser() != null && uid.equals(holder.getUser().getId())))
        .orElse(false);
  }

  /**
   * Checks whether the caller may book deposits onto the account: management unrestricted,
   * employees need {@code can_deposit} (REQ-BANK-009).
   *
   * @param accountId the receiving account; never {@code null}
   * @param authentication the current authentication
   * @return {@code true} iff the caller may deposit
   */
  public boolean canDeposit(@NotNull UUID accountId, Authentication authentication) {
    return hasCapability(accountId, authentication, BankAccountGrant::isCanDeposit);
  }

  /**
   * Checks whether the caller may book withdrawals from the account: management unrestricted,
   * employees need {@code can_withdraw} (REQ-BANK-009).
   *
   * @param accountId the paying account; never {@code null}
   * @param authentication the current authentication
   * @return {@code true} iff the caller may withdraw
   */
  public boolean canWithdraw(@NotNull UUID accountId, Authentication authentication) {
    return hasCapability(accountId, authentication, BankAccountGrant::isCanWithdraw);
  }

  /**
   * Checks whether the caller may transfer out of the account: management unrestricted, employees
   * need {@code can_transfer} on the source account (REQ-BANK-011).
   *
   * @param accountId the source account; never {@code null}
   * @param authentication the current authentication
   * @return {@code true} iff the caller may transfer
   */
  public boolean canTransfer(@NotNull UUID accountId, Authentication authentication) {
    return hasCapability(accountId, authentication, BankAccountGrant::isCanTransfer);
  }

  /**
   * Passes authenticated bank staff that hold the management role or a grant row satisfying the
   * capability predicate.
   *
   * @param accountId the account under decision
   * @param authentication the current authentication, possibly {@code null}
   * @param capability the per-grant check ({@code g -> true} for plain visibility)
   * @return {@code true} iff the caller passes
   */
  private boolean hasCapability(
      @NotNull UUID accountId,
      Authentication authentication,
      @NotNull Predicate<BankAccountGrant> capability) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }
    if (!isBankStaff()) {
      return false;
    }
    if (isManagement()) {
      return true;
    }
    Optional<UUID> userId = authHelperService.currentUserId();
    return userId
        .flatMap(uid -> grantRepository.findById(new BankAccountGrantId(uid, accountId)))
        .filter(capability)
        .isPresent();
  }
}
