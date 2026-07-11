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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankTransferRequest;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for the two orchestration-level guard seams of {@link BankLedgerService#bookTransfer}
 * that fire before any ledger row is written: the destination-visibility gate (REQ-BANK-011) that
 * the orchestrator itself enforces, and the wiring that routes a holder-changing fee-inclusive
 * transfer through {@link BankBookingGuards#requireAmountExceedsFee} (REQ-BANK-033, #999) and
 * aborts the booking when it rejects. Pure Mockito with the extracted {@code BankPostingWriter} /
 * {@code BankBookingGuards} collaborators stubbed — the guards' own decision logic and the
 * KRT-account cap (REQ-BANK-047) are covered directly by {@link BankBookingGuardsTest}, and the
 * account-locking, overdraft and posting arithmetic by the Testcontainers {@code
 * BankLedgerServiceTest} / {@code BankLedgerSplitDepositTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankLedgerServiceGuardTest {

  @Mock private BankTransferFeeService transferFeeService;
  @Mock private BankPostingWriter writer;
  @Mock private BankBookingGuards guards;

  @InjectMocks private BankLedgerService bankLedgerService;

  // ---- bookTransfer destination-visibility gate (REQ-BANK-011) ---------------------------------

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
    verify(writer, never()).lockAccount(any());
    verify(writer, never()).persistTransaction(any(), any(), any(), any(), any(), any(), any());
    verify(writer, never()).persistAccountPosting(any(), any(), any(), any());
    verify(writer, never()).persistHolderPosting(any(), any(), any(), any());
  }

  // ---- bookTransfer fee-inclusive amount<=fee guard wiring (REQ-BANK-033, #999) ----------------

  @Test
  void bookTransfer_feeInclusive_holderChange_delegatesToGuardAndBooksNothingWhenItRejects() {
    // Given: a holder-CHANGING transfer in fee-inclusive mode where a punitive fee consumes the
    // whole entered amount (fee 1 on amount 1 -> amount - fee = 0), so the extracted inclusive
    // guard
    // rejects it.
    UUID sourceAccountId = UUID.randomUUID();
    UUID destinationAccountId = UUID.randomUUID();
    when(writer.lockAccount(sourceAccountId)).thenReturn(areaAccount(sourceAccountId));
    when(writer.lockAccount(destinationAccountId)).thenReturn(areaAccount(destinationAccountId));

    BankHolder sourceHolder = holder();
    BankHolder destinationHolder = holder();
    when(writer.requireHolder(sourceHolder.getId())).thenReturn(sourceHolder);
    when(writer.requireHolder(destinationHolder.getId())).thenReturn(destinationHolder);
    when(transferFeeService.feeOn(new BigDecimal("1"))).thenReturn(new BigDecimal("1"));
    doThrow(
            new BankConflictException(
                BankConflictException.CODE_BANK_FEE_EXCEEDS_AMOUNT,
                "In fee-inclusive mode the entered amount does not exceed the fee",
                Map.of("fee", "1")))
        .when(guards)
        .requireAmountExceedsFee(new BigDecimal("1"), new BigDecimal("1"));

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

    // When / Then: bookTransfer routes the holder-changing inclusive path through the guard and
    // propagates its rejection with the stable code, booking nothing — without the wiring the
    // destination would get a zero/negative credit leg while the source was debited.
    BankConflictException ex =
        assertThrows(
            BankConflictException.class, () -> bankLedgerService.bookTransfer(request, true));
    assertEquals(BankConflictException.CODE_BANK_FEE_EXCEEDS_AMOUNT, ex.getCode());
    assertEquals("1", ex.getProperties().get("fee"));
    verify(writer, never()).persistTransaction(any(), any(), any(), any(), any(), any(), any());
    verify(writer, never()).persistAccountPosting(any(), any(), any(), any());
    verify(writer, never()).persistHolderPosting(any(), any(), any(), any());
  }

  // ---- fixtures --------------------------------------------------------------------------------

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
