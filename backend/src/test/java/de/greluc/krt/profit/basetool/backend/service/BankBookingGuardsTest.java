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
 * Unit tests for {@link BankBookingGuards}: the KRT-account direct-booking cap (REQ-BANK-047) and
 * the {@code amount - fee <= 0} guard (REQ-BANK-033).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankBookingGuardsTest {

  @Mock private BankAccountRepository accountRepository;
  @Mock private BankPostingRepository postingRepository;
  @Mock private AuthHelperService authHelperService;

  @InjectMocks private BankBookingGuards bankBookingGuards;

  @Test
  void exceedsCartelDirectBookingCeiling_plainEmployeeAboveCeiling_returnsTrue() {
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(cartelAccount(accountId, new BigDecimal("1000"))));

    assertTrue(
        bankBookingGuards.exceedsCartelDirectBookingCeiling(accountId, new BigDecimal("1500")));
  }

  @Test
  void exceedsCartelDirectBookingCeiling_atOrBelowCeiling_returnsFalse() {
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(cartelAccount(accountId, new BigDecimal("1000"))));

    assertFalse(
        bankBookingGuards.exceedsCartelDirectBookingCeiling(accountId, new BigDecimal("1000")));
  }

  @Test
  void exceedsCartelDirectBookingCeiling_nullCeilingTreatedAsZero_anyPositiveReturnsTrue() {
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);
    when(accountRepository.findById(accountId))
        .thenReturn(Optional.of(cartelAccount(accountId, null)));

    assertTrue(bankBookingGuards.exceedsCartelDirectBookingCeiling(accountId, new BigDecimal("1")));
  }

  @Test
  void exceedsCartelDirectBookingCeiling_management_returnsFalseEvenAboveCeiling() {
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(true);

    assertFalse(
        bankBookingGuards.exceedsCartelDirectBookingCeiling(
            accountId, new BigDecimal("999999999")));
    verify(accountRepository, never()).findById(any());
  }

  @Test
  void exceedsCartelDirectBookingCeiling_nonCartelAccount_returnsFalse() {
    UUID accountId = UUID.randomUUID();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(areaAccount(accountId)));

    assertFalse(
        bankBookingGuards.exceedsCartelDirectBookingCeiling(
            accountId, new BigDecimal("999999999")));
  }

  @Test
  void requireAmountExceedsFee_amountEqualsFee_throwsFeeExceedsAmount() {
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
    assertDoesNotThrow(
        () -> bankBookingGuards.requireAmountExceedsFee(new BigDecimal("2"), new BigDecimal("1")));
  }

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
