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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankTransferRequest;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for the two <strong>authorization/guard</strong> seams of {@code BankLedgerService}
 * that the ledger-arithmetic suites leave uncovered — the KRT-account direct-booking cap {@code
 * requireCartelDirectBookingAllowed} (REQ-BANK-047) and the two early rejections inside {@link
 * BankLedgerService#bookTransfer} that fire before any ledger row is written: the destination
 * visibility gate (REQ-BANK-011) and the fee-inclusive {@code amount - fee <= 0} guard
 * (REQ-BANK-033, #999) on the holder-changing transfer path. Pure Mockito — these are pre-persist
 * branch decisions driven by the role hierarchy, the {@code CARTEL} type/ceiling and the fee rate,
 * so no database is needed; the account-locking, overdraft and posting arithmetic stay covered by
 * the Testcontainers {@code BankLedgerServiceTest} and {@code BankLedgerSplitDepositTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankLedgerServiceGuardTest {

  @Mock private BankAccountRepository accountRepository;
  @Mock private BankHolderRepository holderRepository;
  @Mock private BankTransactionRepository transactionRepository;
  @Mock private BankPostingRepository postingRepository;
  @Mock private BankHolderPostingRepository holderPostingRepository;
  @Mock private BankAuditService bankAuditService;
  @Mock private BankTransferFeeService transferFeeService;
  @Mock private AuthHelperService authHelperService;

  @InjectMocks private BankLedgerService bankLedgerService;

  // ---- Gap 1: requireCartelDirectBookingAllowed (REQ-BANK-047) ---------------------------------

  @Test
  void requireCartelDirectBookingAllowed_plainEmployeeAboveCeiling_throwsCartelApprovalRequired() {
    // Given: a plain bank employee (not management) and a KRT/CARTEL account with a T1 ceiling of
    // 1000.
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(cartelAccount(accountId, new BigDecimal("1000"))));

    // When / Then: a direct booking above the ceiling routes through external approval.
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.requireCartelDirectBookingAllowed(
                    accountId, new BigDecimal("1500")));
    assertEquals(BankConflictException.CODE_BANK_CARTEL_APPROVAL_REQUIRED, ex.getCode());
    assertEquals("1000", ex.getProperties().get("ceiling"));
  }

  @Test
  void requireCartelDirectBookingAllowed_atOrBelowCeiling_passes() {
    // Given: a plain employee and a CARTEL account with a 1000 ceiling.
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(cartelAccount(accountId, new BigDecimal("1000"))));

    // When / Then: exactly at the ceiling is allowed — the guard is strictly greater-than, so a
    // flipped comparison (>= instead of >) would wrongly reject this boundary amount.
    assertDoesNotThrow(
        () ->
            bankLedgerService.requireCartelDirectBookingAllowed(accountId, new BigDecimal("1000")));
  }

  @Test
  void requireCartelDirectBookingAllowed_nullCeilingTreatedAsZero_rejectsAnyPositive() {
    // Given: a plain employee and a CARTEL account whose ceiling is unset (null -> treated as 0).
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(cartelAccount(accountId, null)));

    // When / Then: with a null ceiling any positive direct booking needs external approval.
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankLedgerService.requireCartelDirectBookingAllowed(
                    accountId, new BigDecimal("1")));
    assertEquals(BankConflictException.CODE_BANK_CARTEL_APPROVAL_REQUIRED, ex.getCode());
    assertEquals("0", ex.getProperties().get("ceiling"), "an unset ceiling is reported as 0");
  }

  @Test
  void requireCartelDirectBookingAllowed_management_bypassesEvenAboveCeiling() {
    // Given: the caller reaches BANK_MANAGEMENT.
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(true);

    // When / Then: management is unrestricted and the method short-circuits before ever loading the
    // account (an inverted role check would instead fall through to findById here).
    assertDoesNotThrow(
        () ->
            bankLedgerService.requireCartelDirectBookingAllowed(
                accountId, new BigDecimal("999999999")));
    verify(accountRepository, never()).findById(any());
  }

  @Test
  void requireCartelDirectBookingAllowed_nonCartelAccount_isNoOp() {
    // Given: a plain employee and a non-CARTEL (AREA) account — the cap is CARTEL-only.
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(areaAccount(accountId)));

    // When / Then: any amount is allowed on a non-CARTEL account regardless of its ceiling.
    assertDoesNotThrow(
        () ->
            bankLedgerService.requireCartelDirectBookingAllowed(
                accountId, new BigDecimal("999999999")));
  }

  // ---- Gap 2: bookTransfer destination-visibility gate (REQ-BANK-011) --------------------------

  @Test
  void bookTransfer_destinationNotVisible_throwsAccessDeniedAndBooksNothing() {
    // Given: a valid transfer to a DIFFERENT account whose destination the caller may not see.
    BankTransferRequest request =
        new BankTransferRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new BigDecimal("100"),
            "Bereichsanteil");

    // When / Then: an invisible destination is refused before any account is locked or booked, so a
    // dropped/inverted guard cannot leak money into an account the caller may not see.
    assertThrows(AccessDeniedException.class, () -> bankLedgerService.bookTransfer(request, false));
    verify(accountRepository, never()).findByIdForUpdate(any());
    verify(postingRepository, never()).save(any());
    verify(holderPostingRepository, never()).save(any());
    verify(transactionRepository, never()).save(any());
  }

  // ---- Gap 3: bookTransfer fee-inclusive amount<=fee guard (REQ-BANK-033, #999) ----------------

  @Test
  void bookTransfer_feeInclusive_holderChange_rejectsWhenAmountDoesNotExceedFee() {
    // Given: a holder-CHANGING transfer in fee-inclusive mode where a punitive fee consumes the
    // whole entered amount (fee 1 on amount 1 -> amount - fee = 0), so nothing would arrive.
    UUID sourceAccountId = UUID.randomUUID();
    UUID destinationAccountId = UUID.randomUUID();
    when(accountRepository.findByIdForUpdate(sourceAccountId))
        .thenReturn(Optional.of(areaAccount(sourceAccountId)));
    when(accountRepository.findByIdForUpdate(destinationAccountId))
        .thenReturn(Optional.of(areaAccount(destinationAccountId)));

    BankHolder sourceHolder = holder();
    BankHolder destinationHolder = holder();
    when(holderRepository.findById(sourceHolder.getId())).thenReturn(Optional.of(sourceHolder));
    when(holderRepository.findById(destinationHolder.getId()))
        .thenReturn(Optional.of(destinationHolder));
    when(transferFeeService.feeOn(new BigDecimal("1"))).thenReturn(new BigDecimal("1"));

    BankTransferRequest request =
        new BankTransferRequest(
            sourceAccountId,
            sourceHolder.getId(),
            destinationAccountId,
            destinationHolder.getId(),
            new BigDecimal("1"),
            "Bereichsanteil",
            null,
            true);

    // When / Then: the inclusive guard rejects the booking with the stable code and books nothing —
    // without it the destination would get a zero/negative credit leg while the source was debited.
    BankConflictException ex =
        assertThrows(
            BankConflictException.class, () -> bankLedgerService.bookTransfer(request, true));
    assertEquals(BankConflictException.CODE_BANK_FEE_EXCEEDS_AMOUNT, ex.getCode());
    assertEquals("1", ex.getProperties().get("fee"));
    verify(postingRepository, never()).save(any());
    verify(holderPostingRepository, never()).save(any());
    verify(transactionRepository, never()).save(any());
  }

  // ---- fixtures --------------------------------------------------------------------------------

  /**
   * Builds an active {@code CARTEL} account with the given direct-booking ceiling.
   *
   * @param id the account id the lookup returns it for
   * @param ceiling the T1 employee-approval ceiling, or {@code null} for an unset ceiling
   * @return the CARTEL account
   */
  private static BankAccount cartelAccount(UUID id, @Nullable BigDecimal ceiling) {
    BankAccount account = new BankAccount();
    account.setId(id);
    account.setAccountNo("KB-CART");
    account.setName("KRT");
    account.setType(BankAccountType.CARTEL);
    account.setStatus(BankAccountStatus.ACTIVE);
    account.setEmployeeApprovalCeiling(ceiling);
    return account;
  }

  /**
   * Builds an active {@code AREA} account — a non-CARTEL, justification-optional type.
   *
   * @param id the account id the lookup returns it for
   * @return the AREA account
   */
  private static BankAccount areaAccount(UUID id) {
    BankAccount account = new BankAccount();
    account.setId(id);
    account.setAccountNo("KB-" + id.toString().substring(0, 4));
    account.setName("Bereichskonto");
    account.setType(BankAccountType.AREA);
    account.setAreaName("Profit");
    account.setStatus(BankAccountStatus.ACTIVE);
    return account;
  }

  /**
   * Builds an active receiving holder with a fresh id.
   *
   * @return the holder
   */
  private static BankHolder holder() {
    BankHolder holder = new BankHolder();
    holder.setId(UUID.randomUUID());
    holder.setHandle("custodian-" + UUID.randomUUID());
    holder.setActive(true);
    return holder;
  }
}
