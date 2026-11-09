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

import de.greluc.krt.profit.basetool.backend.bank.api.BankAccessPolicy;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffRights;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @PreAuthorize} helper bean of the bank-staff surface (ADR-0011): a thin adapter that
 * asks {@link BankAccessPolicy} for the request's {@link BankCaller#actor()} and decides nothing
 * itself (ADR-0352). The authentication argument of the gates is the SpEL contract; the actor comes
 * from the database.
 */
@Service("bankSecurityService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankSecurityService {

  private final BankCaller bankCaller;
  private final BankAccessRules policy;
  private final BankHolderRepository holderRepository;

  /**
   * The current caller's bank-staff rights.
   *
   * @return the rights of {@link BankCaller#actor()}
   */
  @NotNull
  public BankStaffRights staffRights() {
    return policy.staffRights(bankCaller.actor());
  }

  /**
   * Whether the current caller is Bankleitung or above and so sees and manages every account,
   * holder and grant (REQ-BANK-010).
   *
   * @return {@link BankStaffRights#management()} of the caller
   */
  public boolean isManagement() {
    return staffRights().management();
  }

  /**
   * Whether the caller may see the account on the bank-staff side (REQ-BANK-009).
   *
   * @param accountId the account to check
   * @param authentication the current authentication, the SpEL argument of the gate
   * @return {@link BankStaffRights#canSee(UUID)} of the caller
   */
  public boolean canSee(@NotNull UUID accountId, Authentication authentication) {
    return staffRights().canSee(accountId);
  }

  /**
   * Whether the caller may see a holder's custody history (REQ-BANK-032).
   *
   * @param holderId the holder whose history is requested
   * @param authentication the current authentication, the SpEL argument of the gate
   * @return {@link BankStaffRights#maySeeHolder(UUID)} for the member the holder belongs to
   */
  public boolean canSeeHolder(@NotNull UUID holderId, Authentication authentication) {
    return staffRights()
        .maySeeHolder(
            holderRepository
                .findById(holderId)
                .map(holder -> holder.getUser() == null ? null : holder.getUser().getId())
                .orElse(null));
  }

  /**
   * Whether the caller may book deposits onto the account (REQ-BANK-009).
   *
   * @param accountId the receiving account
   * @param authentication the current authentication, the SpEL argument of the gate
   * @return {@code true} iff the caller holds the deposit capability
   */
  public boolean canDeposit(@NotNull UUID accountId, Authentication authentication) {
    return staffRights().canBook(BankBookingRequestType.DEPOSIT, accountId);
  }

  /**
   * Whether the caller may book withdrawals from the account (REQ-BANK-009).
   *
   * @param accountId the paying account
   * @param authentication the current authentication, the SpEL argument of the gate
   * @return {@code true} iff the caller holds the withdrawal capability
   */
  public boolean canWithdraw(@NotNull UUID accountId, Authentication authentication) {
    return staffRights().canBook(BankBookingRequestType.WITHDRAWAL, accountId);
  }

  /**
   * Whether the caller may transfer out of the account (REQ-BANK-011).
   *
   * @param accountId the source account
   * @param authentication the current authentication, the SpEL argument of the gate
   * @return {@code true} iff the caller holds the transfer capability
   */
  public boolean canTransfer(@NotNull UUID accountId, Authentication authentication) {
    return staffRights().canBook(BankBookingRequestType.TRANSFER, accountId);
  }

  /**
   * Whether the caller's direct withdrawal or transfer must become an approval request instead
   * (REQ-BANK-047, ADR-0109).
   *
   * @param accountId the (source) account
   * @param amount the entered whole-aUEC amount leaving the account
   * @return {@code true} iff the booking exceeds the KRT ceiling for the caller
   */
  public boolean exceedsCartelDirectBookingCeiling(
      @NotNull UUID accountId, @NotNull BigDecimal amount) {
    return policy.exceedsDirectBookingCeiling(bankCaller.actor(), accountId, amount);
  }
}
