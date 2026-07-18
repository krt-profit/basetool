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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankPostingRepository;
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

/**
 * Unit tests for the pre-persist validation guards {@link BankBookingGuards} extracted from {@code
 * BankLedgerService} (#1253) — the KRT-account direct-booking cap {@code
 * exceedsCartelDirectBookingCeiling} (REQ-BANK-047, ADR-0109) and the fee-inclusive {@code amount -
 * fee <= 0} guard {@code requireAmountExceedsFee} (REQ-BANK-033, #999). Pure Mockito — these are
 * pre-persist branch decisions driven by the role hierarchy, the {@code CARTEL} type/ceiling and
 * the fee, so no database is needed. The overdraft, closed-account and holder-activity guards stay
 * covered by the Testcontainers booking suites that drive them end-to-end ({@code
 * BankLedgerServiceTest}, {@code BankLedgerSplitDepositTest}, {@code BankHolderTransferFeeTest}).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankBookingGuardsTest {

  @Mock private BankAccountRepository accountRepository;
  @Mock private BankPostingRepository postingRepository;
  @Mock private AuthHelperService authHelperService;

  @InjectMocks private BankBookingGuards bankBookingGuards;

  // ---- exceedsCartelDirectBookingCeiling (REQ-BANK-047, ADR-0109) -------------------------------

  @Test
  void exceedsCartelDirectBookingCeiling_plainEmployeeAboveCeiling_returnsTrue() {
    // Given: a plain bank employee (not management) and a KRT/CARTEL account with a T1 ceiling of
    // 1000.
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(cartelAccount(accountId, new BigDecimal("1000"))));

    // When / Then: a direct booking above the ceiling must be re-routed to an approval request.
    assertTrue(
        bankBookingGuards.exceedsCartelDirectBookingCeiling(accountId, new BigDecimal("1500")));
  }

  @Test
  void exceedsCartelDirectBookingCeiling_atOrBelowCeiling_returnsFalse() {
    // Given: a plain employee and a CARTEL account with a 1000 ceiling.
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(cartelAccount(accountId, new BigDecimal("1000"))));

    // When / Then: exactly at the ceiling books directly — the guard is strictly greater-than, so a
    // flipped comparison (>= instead of >) would wrongly re-route this boundary amount.
    assertFalse(
        bankBookingGuards.exceedsCartelDirectBookingCeiling(accountId, new BigDecimal("1000")));
  }

  @Test
  void exceedsCartelDirectBookingCeiling_nullCeilingTreatedAsZero_anyPositiveReturnsTrue() {
    // Given: a plain employee and a CARTEL account whose ceiling is unset (null -> treated as 0).
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(cartelAccount(accountId, null)));

    // When / Then: with a null ceiling any positive direct booking needs approval.
    assertTrue(bankBookingGuards.exceedsCartelDirectBookingCeiling(accountId, new BigDecimal("1")));
  }

  @Test
  void exceedsCartelDirectBookingCeiling_management_returnsFalseEvenAboveCeiling() {
    // Given: the caller reaches BANK_MANAGEMENT.
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(true);

    // When / Then: management is uncapped and short-circuits before ever loading the account (an
    // inverted role check would instead fall through to findById here).
    assertFalse(
        bankBookingGuards.exceedsCartelDirectBookingCeiling(
            accountId, new BigDecimal("999999999")));
    verify(accountRepository, never()).findById(any());
  }

  @Test
  void exceedsCartelDirectBookingCeiling_nonCartelAccount_returnsFalse() {
    // Given: a plain employee and a non-CARTEL (AREA) account — the cap is CARTEL-only.
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(areaAccount(accountId)));

    // When / Then: any amount books directly on a non-CARTEL account regardless of its ceiling.
    assertFalse(
        bankBookingGuards.exceedsCartelDirectBookingCeiling(
            accountId, new BigDecimal("999999999")));
  }

  // ---- requireAmountExceedsFee (REQ-BANK-033, #999) --------------------------------------------

  @Test
  void requireAmountExceedsFee_amountEqualsFee_throwsFeeExceedsAmount() {
    // Given / When / Then: a punitive fee that consumes the whole entered amount (fee 1 on amount 1
    // -> amount - fee = 0) leaves nothing to arrive, so the inclusive guard rejects it with the
    // stable code and reports the fee.
    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                bankBookingGuards.requireAmountExceedsFee(
                    new BigDecimal("1"), new BigDecimal("1")));
    assertEquals(BankConflictException.CODE_BANK_FEE_EXCEEDS_AMOUNT, ex.getCode());
    assertEquals("1", ex.getProperties().get("fee"));
  }

  @Test
  void requireAmountExceedsFee_amountExceedsFee_passes() {
    // When / Then: as long as the entered amount strictly exceeds the fee, something arrives and
    // the
    // guard passes — the boundary is strictly greater-than.
    assertDoesNotThrow(
        () -> bankBookingGuards.requireAmountExceedsFee(new BigDecimal("2"), new BigDecimal("1")));
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
}
