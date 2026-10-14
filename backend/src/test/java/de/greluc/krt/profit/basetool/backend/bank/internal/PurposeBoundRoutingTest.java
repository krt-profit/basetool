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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How a request that another module raises for a purpose is routed through the bank's approval
 * ladder (REQ-BANK-061, REQ-BANK-047).
 */
class PurposeBoundRoutingTest {

  private static final BigDecimal T1 = new BigDecimal("100");
  private static final BigDecimal T2 = new BigDecimal("1000");

  private BankAccountRepository accountRepository;
  private BankBookingRequestService requestService;
  private OrgUnitBankAccessService service;

  private final UUID accountId = UUID.randomUUID();
  private final UUID recipient = UUID.randomUUID();
  private final BankPurpose purpose =
      new BankPurpose(BankPurposeKind.PURCHASING_DEAL, UUID.randomUUID());

  @BeforeEach
  void setUp() {
    accountRepository = mock(BankAccountRepository.class);
    requestService = mock(BankBookingRequestService.class);
    service =
        new OrgUnitBankAccessService(
            null,
            null,
            accountRepository,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            requestService,
            null,
            null,
            null,
            null);
  }

  private BankAccount account(BankAccountType type, BankAccountStatus status) {
    BankAccount account = new BankAccount();
    account.setId(accountId);
    account.setType(type);
    account.setStatus(status);
    account.setEmployeeApprovalCeiling(T1);
    account.setAreaLeadApprovalCeiling(T2);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    return account;
  }

  private void raise(BankBookingRequestType type, String amount) {
    service.raisePurposeBoundRequest(
        accountId, type, new BigDecimal(amount), "Ankauf 12 Quantainium", recipient, purpose);
  }

  private void verifyRouting(
      BankBookingRequestType type,
      String amount,
      boolean ownerApproval,
      BigDecimal limit,
      BankRequestApprover approver) {
    verify(requestService)
        .create(
            eq(accountId),
            eq(type),
            eq(new BigDecimal(amount)),
            eq(null),
            eq("Ankauf 12 Quantainium"),
            eq(null),
            eq(ownerApproval),
            eq(limit),
            eq(approver),
            eq(false),
            eq(null),
            eq(recipient),
            eq(null),
            eq(purpose));
  }

  @Test
  void aKrtWithdrawalUpToTheEmployeeCeilingNeedsNoOwnerApproval() {
    account(BankAccountType.CARTEL, BankAccountStatus.ACTIVE);

    raise(BankBookingRequestType.WITHDRAWAL, "80");

    verifyRouting(BankBookingRequestType.WITHDRAWAL, "80", false, T1, null);
  }

  @Test
  void aKrtWithdrawalAboveTheCeilingNeedsTheBankManagement() {
    account(BankAccountType.CARTEL, BankAccountStatus.ACTIVE);

    raise(BankBookingRequestType.WITHDRAWAL, "500");

    verifyRouting(
        BankBookingRequestType.WITHDRAWAL, "500", true, T1, BankRequestApprover.BANK_MANAGEMENT);
  }

  @Test
  void aKrtWithdrawalAboveTheAreaLeadCeilingNeedsTheOrganisationsleitung() {
    account(BankAccountType.CARTEL, BankAccountStatus.ACTIVE);

    raise(BankBookingRequestType.WITHDRAWAL, "5000");

    verifyRouting(
        BankBookingRequestType.WITHDRAWAL,
        "5000",
        true,
        T1,
        BankRequestApprover.ORGANISATIONSLEITUNG);
  }

  @Test
  void aSpecialAccountWithdrawalNeedsNoOwnerApprovalBeforeTheBankConfirms() {
    account(BankAccountType.SPECIAL, BankAccountStatus.ACTIVE);

    raise(BankBookingRequestType.WITHDRAWAL, "5000");

    verifyRouting(BankBookingRequestType.WITHDRAWAL, "5000", false, null, null);
  }

  @Test
  void aDepositNeverNeedsAnOwnerApproval() {
    account(BankAccountType.CARTEL, BankAccountStatus.ACTIVE);

    raise(BankBookingRequestType.DEPOSIT, "5000");

    verifyRouting(BankBookingRequestType.DEPOSIT, "5000", false, null, null);
  }

  @Test
  void anOrgUnitAccountIsNotAFundingAccount() {
    account(BankAccountType.ORG_UNIT, BankAccountStatus.ACTIVE);

    assertThrows(BadRequestException.class, () -> raise(BankBookingRequestType.WITHDRAWAL, "80"));
    verify(requestService, never())
        .create(
            any(), any(), any(), any(), any(), any(), eq(false), any(), any(), eq(false), any(),
            any(), any(), any());
  }

  @Test
  void aClosedFundingAccountTakesNoRequest() {
    account(BankAccountType.CARTEL, BankAccountStatus.CLOSED);

    assertThrows(BadRequestException.class, () -> raise(BankBookingRequestType.WITHDRAWAL, "80"));
  }

  @Test
  void aTransferIsNeitherADepositNorAWithdrawal() {
    account(BankAccountType.CARTEL, BankAccountStatus.ACTIVE);

    assertThrows(BadRequestException.class, () -> raise(BankBookingRequestType.TRANSFER, "80"));
  }

  @Test
  void anUnknownAccountIsNotFound() {
    when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> raise(BankBookingRequestType.WITHDRAWAL, "80"));
  }
}
