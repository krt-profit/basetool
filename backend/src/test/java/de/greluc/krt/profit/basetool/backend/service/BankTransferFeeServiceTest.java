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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BankTransferFeeService} (REQ-BANK-033, ADR-0052): the whole-aUEC fee
 * rounding, the total-debit (amount + fee) derivation borne by the source, and the runtime-rate
 * resolution with its fallbacks — mirroring the operation payout's rate handling but rounding the
 * fee to whole aUEC.
 */
@ExtendWith(MockitoExtension.class)
class BankTransferFeeServiceTest {

  @Mock private SystemSettingService systemSettingService;

  @InjectMocks private BankTransferFeeService bankTransferFeeService;

  private void rate(String value) {
    when(systemSettingService.getSettingValue("operation.transfer_fee_rate"))
        .thenReturn(Optional.of(value));
  }

  @Test
  void feeOn_roundsToWholeAuecHalfUp() {
    rate("0.005");

    assertEquals(
        0, bankTransferFeeService.feeOn(new BigDecimal("1000")).compareTo(new BigDecimal("5")));
    assertEquals(
        0, bankTransferFeeService.feeOn(new BigDecimal("300")).compareTo(new BigDecimal("2")));
    assertEquals(0, bankTransferFeeService.feeOn(new BigDecimal("100")).compareTo(BigDecimal.ONE));
  }

  @Test
  void totalDebit_isAmountPlusFee() {
    rate("0.005");

    assertEquals(
        0,
        bankTransferFeeService
            .totalDebit(new BigDecimal("1000"))
            .compareTo(new BigDecimal("1005")));
    assertEquals(
        0,
        bankTransferFeeService
            .totalDebit(new BigDecimal("500000"))
            .compareTo(new BigDecimal("502500")));
  }

  @Test
  void feeOn_nonPositiveGross_isZero() {
    assertEquals(0, bankTransferFeeService.feeOn(BigDecimal.ZERO).signum());
    assertEquals(0, bankTransferFeeService.feeOn(new BigDecimal("-50")).signum());
  }

  @Test
  void resolveRate_usesPersistedValue() {
    rate("0.01");
    assertEquals(
        0, bankTransferFeeService.resolveTransferFeeRate().compareTo(new BigDecimal("0.01")));
  }

  @Test
  void resolveRate_fallsBackOnMissingBlankInvalidOrOutOfRange() {
    when(systemSettingService.getSettingValue("operation.transfer_fee_rate"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of("  "))
        .thenReturn(Optional.of("abc"))
        .thenReturn(Optional.of("1.0"));
    BigDecimal expected = new BigDecimal("0.005");
    assertEquals(0, bankTransferFeeService.resolveTransferFeeRate().compareTo(expected), "missing");
    assertEquals(0, bankTransferFeeService.resolveTransferFeeRate().compareTo(expected), "blank");
    assertEquals(0, bankTransferFeeService.resolveTransferFeeRate().compareTo(expected), "invalid");
    assertEquals(
        0,
        bankTransferFeeService.resolveTransferFeeRate().compareTo(expected),
        "out of range >= 1");
  }
}
