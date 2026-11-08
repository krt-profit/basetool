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

import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountAccess;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountRight;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The org-unit side's adapter onto {@link BankAccessRules} (ADR-0020, ADR-0352): it reads who calls
 * ({@link BankCaller}) and the active-org-unit pin, asks the policy, and serves the read side of
 * the org-unit bank page through the org-unit-blind bank code ({@link BankAccountService}, {@link
 * BankStatementReportService}). It decides nothing itself.
 *
 * <p>{@link OrgUnitBankSettingsService} and {@link OrgUnitBankRequestService} load the accounts
 * they change through it.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBankAccessService {

  /**
   * Read-only capabilities handed to org-unit viewers — no deposit/withdraw/transfer (management).
   */
  private static final BankCapabilitiesDto READ_ONLY_CAPABILITIES =
      new BankCapabilitiesDto(false, false, false, false);

  /** The problem detail of the 404 for an unknown or hidden account id (REQ-BANK-066). */
  static final String ACCOUNT_NOT_FOUND = "Bank account not found";

  private final RequestScopeResolver requestScopeResolver;
  private final BankCaller bankCaller;
  private final BankAccessRules policy;
  private final BankAccountRepository bankAccountRepository;
  private final BankPostingRepository bankPostingRepository;
  private final BankAccountService bankAccountService;
  private final BankStatementReportService bankStatementReportService;

  /**
   * Lists every active account the caller may view under the pin, with balance, 30-day trend,
   * balance target and settings affordance (REQ-BANK-021).
   *
   * @return the visible account balances ordered by account number; empty when none
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<OrgUnitBankBalanceDto> listOverseenOrgUnitBalances() {
    List<BankAccessRules.ViewableAccount> visible = policy.viewable(actor(), pin());
    if (visible.isEmpty()) {
      return List.of();
    }
    BankAccountFigures figures =
        BankAccountFigures.load(
            bankPostingRepository,
            visible.stream().map(BankAccessRules.ViewableAccount::account).toList());
    return visible.stream().map(viewable -> toDto(viewable, figures)).toList();
  }

  /**
   * Lists every active account the caller may view on the org-unit bank page over their whole
   * reach, ignoring the active-org-unit pin (REQ-BANK-064).
   *
   * @return the visible active accounts ordered by account number; empty when none
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<BankAccount> listViewableActiveAccounts() {
    return policy.viewable(actor(), null).stream()
        .map(BankAccessRules.ViewableAccount::account)
        .toList();
  }

  /**
   * The org units of the caller's active context, which only orders the account list
   * (REQ-BANK-064); see {@link BankAccessRules#activeContext}.
   *
   * @return the active-context org-unit ids; empty for a caller without memberships and pin
   */
  @NotNull
  public Set<UUID> activeContextOrgUnitIds() {
    return policy.activeContext(actor(), pin());
  }

  /**
   * Returns the read-only detail of an account the caller may view (REQ-BANK-038), with all
   * bank-staff capabilities off.
   *
   * @param accountId the account to open
   * @return the read-only detail
   * @throws NotFoundException when the account does not exist or the caller may not view it
   */
  @NotNull
  @Transactional(readOnly = true)
  public OrgUnitBankAccountDetailDto getViewableAccountDetail(@NotNull UUID accountId) {
    BankAccountAccess.Visible access =
        requireAccess(accountId, visible -> visible.has(BankAccountRight.VIEW)).access();
    BankAccountDetailDto detail =
        bankAccountService.getAccountDetail(accountId, READ_ONLY_CAPABILITIES);
    return new OrgUnitBankAccountDetailDto(
        detail,
        true,
        access.has(BankAccountRight.SET_TARGET),
        access.has(BankAccountRight.CONFIGURE_VISIBILITY),
        access.has(BankAccountRight.REQUEST),
        access.has(BankAccountRight.CONFIGURE_APPROVAL_LIMITS),
        access.approvalLimit(),
        access.approvalExempt());
  }

  /**
   * Returns one page of an account's booking history with the holder handles redacted
   * (REQ-BANK-038).
   *
   * @param accountId the account to read
   * @param pageable page, size and whitelisted sort
   * @param from inclusive lower bound on the booking instant, or {@code null}
   * @param to inclusive upper bound on the booking instant, or {@code null}
   * @return one page of redacted booking rows
   * @throws NotFoundException when the account does not exist or the caller may not view it
   */
  @NotNull
  @Transactional(readOnly = true)
  public Page<BankBookingDto> getViewableAccountBookings(
      @NotNull UUID accountId,
      @NotNull Pageable pageable,
      @Nullable Instant from,
      @Nullable Instant to) {
    requireViewable(accountId);
    return bankAccountService
        .getBookings(accountId, pageable, from, to)
        .map(OrgUnitBankAccessService::redact);
  }

  /**
   * Returns the balance-over-time series of an account the caller may view (REQ-BANK-049).
   *
   * @param accountId the account
   * @param from inclusive period start
   * @param to inclusive period end
   * @return the balance series and the account's balance target
   * @throws NotFoundException when the account does not exist or the caller may not view it
   */
  @NotNull
  @Transactional(readOnly = true)
  public BankBalanceSeriesDto getViewableBalanceSeries(
      @NotNull UUID accountId, @NotNull Instant from, @NotNull Instant to) {
    requireViewable(accountId);
    return bankAccountService.getBalanceSeries(accountId, from, to);
  }

  /**
   * Generates the holder-redacted statement PDF for an account the caller may view (REQ-BANK-038)
   * and records the {@code STATEMENT_EXPORTED} audit event.
   *
   * @param accountId the account
   * @param from inclusive period start
   * @param to inclusive period end
   * @param userZone the zone to render timestamps in, or {@code null} for UTC
   * @return the redacted PDF bytes
   * @throws NotFoundException when the account does not exist or the caller may not view it
   */
  @NotNull
  @Transactional
  public byte[] exportViewableStatement(
      @NotNull UUID accountId,
      @NotNull Instant from,
      @NotNull Instant to,
      @Nullable ZoneId userZone) {
    requireViewable(accountId);
    return bankStatementReportService.generateStatement(accountId, from, to, userZone, true);
  }

  /**
   * The caller's access to an account they already loaded, under the active pin.
   *
   * @param account the account
   * @return the caller's access
   */
  @NotNull
  @Transactional(readOnly = true)
  public BankAccountAccess accessTo(@NotNull BankAccount account) {
    return policy.accessTo(actor(), account, pin());
  }

  /**
   * Loads an account the org-unit side discloses to the caller and on which the caller holds the
   * right (REQ-BANK-066): an undisclosed account fails like an unknown id, a disclosed one without
   * the right with {@link AccessDeniedException}.
   *
   * @param accountId the account id
   * @param right the right the change needs
   * @return the account entity
   * @throws NotFoundException when the account does not exist or is hidden from the caller
   * @throws AccessDeniedException when the caller lacks the right
   */
  @NotNull
  @Transactional(readOnly = true)
  public BankAccount requireCanChange(@NotNull UUID accountId, @NotNull BankAccountRight right) {
    return requireDisclosedAccount(
        accountId, access -> access.has(right), "The caller lacks " + right + " on this account");
  }

  /**
   * Loads an account whose settings panel the caller may open: disclosed, and the caller may set
   * the target or configure the visibility (REQ-BANK-035).
   *
   * @param accountId the account id
   * @return the account entity
   * @throws NotFoundException when the account does not exist or is hidden from the caller
   * @throws AccessDeniedException when the caller may manage none of its settings
   */
  @NotNull
  @Transactional(readOnly = true)
  public BankAccount requireSettingsAccount(@NotNull UUID accountId) {
    return requireDisclosedAccount(
        accountId,
        BankAccountAccess.Visible::canManageSettings,
        "The caller may not manage this account's settings");
  }

  /**
   * Loads a disclosed account and checks the caller's access.
   *
   * @param accountId the account id
   * @param permitted what the change needs of the caller's access
   * @param denial the message of the 403
   * @return the account entity
   * @throws NotFoundException when the account does not exist or is hidden from the caller
   * @throws AccessDeniedException when {@code permitted} fails
   */
  @NotNull
  private BankAccount requireDisclosedAccount(
      @NotNull UUID accountId,
      @NotNull Predicate<BankAccountAccess.Visible> permitted,
      @NotNull String denial) {
    AccessedAccount accessed =
        requireAccess(accountId, BankAccountAccess.Visible::disclosedOnOrgUnitSide);
    if (!permitted.test(accessed.access())) {
      throw new AccessDeniedException(denial);
    }
    return accessed.account();
  }

  /**
   * Asserts the caller may view the account.
   *
   * @param accountId the account id
   * @throws NotFoundException when the account does not exist or the caller may not view it
   */
  private void requireViewable(@NotNull UUID accountId) {
    requireAccess(accountId, visible -> visible.has(BankAccountRight.VIEW));
  }

  /**
   * Loads an account and the caller's access to it; an account the predicate rejects fails exactly
   * like an unknown id (REQ-BANK-066).
   *
   * @param accountId the account id
   * @param shown what the caller's access must satisfy for the account to exist for them
   * @return the account and the access
   * @throws NotFoundException when the account does not exist or is not shown
   */
  @NotNull
  private AccessedAccount requireAccess(
      @NotNull UUID accountId, @NotNull Predicate<BankAccountAccess.Visible> shown) {
    BankAccount account =
        bankAccountRepository
            .findById(accountId)
            .orElseThrow(OrgUnitBankAccessService::accountNotFound);
    if (accessTo(account) instanceof BankAccountAccess.Visible visible && shown.test(visible)) {
      return new AccessedAccount(account, visible);
    }
    throw accountNotFound();
  }

  /**
   * The one 404 every org-unit bank endpoint answers for an unknown or hidden account id
   * (REQ-BANK-066).
   *
   * @return the not-found exception carrying the shared problem detail
   */
  @NotNull
  private static NotFoundException accountNotFound() {
    return new NotFoundException(ACCOUNT_NOT_FOUND);
  }

  /**
   * The request's actor.
   *
   * @return the caller's actor
   */
  @NotNull
  BankActor actor() {
    return bankCaller.actor();
  }

  /**
   * The request's active-org-unit pin as sent; the policy decides what it narrows.
   *
   * @return the pinned org unit, or {@code null}
   */
  @Nullable
  UUID pin() {
    return requestScopeResolver.readActiveSquadronFromHeader().orElse(null);
  }

  /**
   * Redacts a booking row for an org-unit viewer (REQ-BANK-038): nulls the holder and
   * counter-holder handles and the internal staff note; the counterparty stays visible.
   *
   * @param booking the bank-staff booking row
   * @return a redacted copy
   */
  @NotNull
  private static BankBookingDto redact(@NotNull BankBookingDto booking) {
    return new BankBookingDto(
        booking.postingId(),
        booking.transactionId(),
        booking.type(),
        booking.amount(),
        null,
        booking.note(),
        booking.justification(),
        null,
        booking.createdAt(),
        booking.reversedTransactionId(),
        booking.counterAccountNo(),
        booking.counterAccountName(),
        null,
        booking.intraAccount(),
        booking.transferFee(),
        booking.counterpartyHandle(),
        booking.counterpartyOrgUnitName());
  }

  /**
   * Projects a viewable account and its figures into the balance-card DTO.
   *
   * @param viewable the account with the caller's access
   * @param figures the balances and slices of the listed accounts
   * @return the balance-card DTO
   */
  @NotNull
  private static OrgUnitBankBalanceDto toDto(
      @NotNull BankAccessRules.ViewableAccount viewable, @NotNull BankAccountFigures figures) {
    BankAccount account = viewable.account();
    BankAccountAccess.Visible access = viewable.access();
    BigDecimal balance = figures.balanceOf(account.getId());
    List<BankPostingSlice> slices = figures.slicesOf(account.getId());
    BigDecimal delta = BankTrendCalculator.windowDelta(slices);
    OrgUnit orgUnit = account.getOrgUnit();
    return new OrgUnitBankBalanceDto(
        account.getId(),
        account.getAccountNo(),
        account.getName(),
        account.getStatus(),
        account.getType(),
        orgUnit == null ? null : orgUnit.getId(),
        orgUnit == null ? null : orgUnit.getName(),
        orgUnit == null ? null : orgUnit.getShorthand(),
        orgUnit == null ? null : orgUnit.getKind(),
        balance,
        access.has(BankAccountRight.REQUEST),
        delta,
        BankTrendCalculator.sparkline(balance, delta, slices),
        account.getBalanceTarget(),
        access.canManageSettings(),
        access.approvalLimit(),
        access.approvalExempt());
  }

  /**
   * An account with the caller's access to it.
   *
   * @param account the account
   * @param access the caller's access
   */
  private record AccessedAccount(
      @NotNull BankAccount account, @NotNull BankAccountAccess.Visible access) {}
}
