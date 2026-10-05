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

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.BankAccountGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountGrantId;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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
@Service("bankSecurityService")
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
   * Checks whether the caller may confirm a booking request of the given type on the account: the
   * capability the matching direct booking needs, {@code can_deposit}, {@code can_withdraw} or
   * {@code can_transfer} on the request's account; management unrestricted (REQ-BANK-023).
   *
   * @param type the request's movement kind
   * @param accountId the request's (source) account; never {@code null}
   * @param authentication the current authentication
   * @return {@code true} iff the caller may confirm such a request
   */
  public boolean canConfirm(
      @NotNull BankBookingRequestType type,
      @NotNull UUID accountId,
      Authentication authentication) {
    return hasCapability(accountId, authentication, confirmCapability(type), this::findCallerGrant);
  }

  /**
   * Answers {@link #canConfirm} for any number of requests with at most one grant read, for a list
   * that marks every row (REQ-BANK-023).
   *
   * @param authentication the current authentication
   * @return a check taking a request's type and account id, deciding exactly as {@link #canConfirm}
   */
  @NotNull
  public BiPredicate<BankBookingRequestType, UUID> confirmCheck(Authentication authentication) {
    Map<UUID, BankAccountGrant> callerGrants = isManagement() ? Map.of() : loadCallerGrants();
    return (type, accountId) ->
        hasCapability(
            accountId,
            authentication,
            confirmCapability(type),
            id -> Optional.ofNullable(callerGrants.get(id)));
  }

  /**
   * Maps a request type onto the grant flag its confirmation needs.
   *
   * @param type the request's movement kind
   * @return the per-grant check for that kind
   */
  @NotNull
  private static Predicate<BankAccountGrant> confirmCapability(
      @NotNull BankBookingRequestType type) {
    return switch (type) {
      case DEPOSIT -> BankAccountGrant::isCanDeposit;
      case WITHDRAWAL -> BankAccountGrant::isCanWithdraw;
      case TRANSFER -> BankAccountGrant::isCanTransfer;
    };
  }

  /**
   * Loads every grant row of the caller, keyed by account id.
   *
   * @return the caller's grants; empty when there is no caller
   */
  @NotNull
  private Map<UUID, BankAccountGrant> loadCallerGrants() {
    return authHelperService
        .currentUserId()
        .map(
            uid ->
                grantRepository.findByUserId(uid).stream()
                    .collect(
                        Collectors.toUnmodifiableMap(
                            grant -> grant.getId().getAccountId(), Function.identity())))
        .orElseGet(Map::of);
  }

  /**
   * Reads the caller's grant row on one account.
   *
   * @param accountId the account
   * @return the caller's grant on it, or empty when there is none or no caller
   */
  @NotNull
  private Optional<BankAccountGrant> findCallerGrant(@NotNull UUID accountId) {
    return authHelperService
        .currentUserId()
        .flatMap(uid -> grantRepository.findById(new BankAccountGrantId(uid, accountId)));
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
    return hasCapability(accountId, authentication, capability, this::findCallerGrant);
  }

  /**
   * Passes authenticated bank staff that hold the management role or a grant row, found through the
   * given lookup, satisfying the capability predicate.
   *
   * @param accountId the account under decision
   * @param authentication the current authentication, possibly {@code null}
   * @param capability the per-grant check
   * @param callerGrant resolves the caller's grant row on an account
   * @return {@code true} iff the caller passes
   */
  private boolean hasCapability(
      @NotNull UUID accountId,
      Authentication authentication,
      @NotNull Predicate<BankAccountGrant> capability,
      @NotNull Function<UUID, Optional<BankAccountGrant>> callerGrant) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return false;
    }
    if (!isBankStaff()) {
      return false;
    }
    if (isManagement()) {
      return true;
    }
    return callerGrant.apply(accountId).filter(capability).isPresent();
  }
}
