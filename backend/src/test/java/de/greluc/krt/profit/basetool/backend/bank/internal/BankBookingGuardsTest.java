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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.greluc.krt.profit.basetool.backend.bank.api.BankConflictException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link BankBookingGuards}: the {@code amount - fee <= 0} guard (REQ-BANK-033). */
@ExtendWith(MockitoExtension.class)
class BankBookingGuardsTest {

  @Mock private BankPostingRepository postingRepository;

  @InjectMocks private BankBookingGuards bankBookingGuards;

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
}
