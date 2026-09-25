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

import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for the period-filtered booking-history query against real Postgres
 * (REQ-BANK-051), verifying the cast temporal bounds plan with non-null values.
 */
@SpringBootTest
@ActiveProfiles("test")
class BankBookingHistoryPeriodFilterTest {

  @Autowired private BankAccountService bankAccountService;
  @Autowired private BankLedgerService bankLedgerService;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private BankHolderRepository holderRepository;

  @Test
  void periodFilteredHistoryRunsOnPostgresAndFiltersByWindow() {
    BankHolder holder = newHolder("period-holder-" + UUID.randomUUID());
    BankAccount account = newAccount("Period Konto " + UUID.randomUUID());
    bankLedgerService.bookDeposit(
        new BankDepositRequest(account.getId(), holder.getId(), new BigDecimal("4242"), null));
    Instant now = Instant.now();
    Instant ninetyDaysAgo = now.minus(Duration.ofDays(90));

    Page<BankBookingDto> windowed =
        bankAccountService.getBookings(
            account.getId(), PageRequest.of(0, 50), ninetyDaysAgo, now.plusSeconds(60));
    assertEquals(
        1,
        windowed.getTotalElements(),
        "the last-90-days window includes a deposit booked moments ago");

    Page<BankBookingDto> pastWindow =
        bankAccountService.getBookings(
            account.getId(),
            PageRequest.of(0, 50),
            now.minus(Duration.ofDays(90)),
            now.minus(Duration.ofDays(30)));
    assertEquals(
        0, pastWindow.getTotalElements(), "a bygone window excludes the just-booked deposit");

    Page<BankBookingDto> unbounded =
        bankAccountService.getBookings(account.getId(), PageRequest.of(0, 50), null, null);
    assertEquals(
        1, unbounded.getTotalElements(), "the unbounded history returns the account's postings");
  }

  /** Persists a fresh SPECIAL account with the next free account number. */
  private BankAccount newAccount(String name) {
    BankAccount account = new BankAccount();
    account.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    account.setName(name);
    account.setType(BankAccountType.SPECIAL);
    account.setStatus(BankAccountStatus.ACTIVE);
    return accountRepository.save(account);
  }

  /** Persists an active holder with the given handle. */
  private BankHolder newHolder(String handle) {
    BankHolder holder = new BankHolder();
    holder.setHandle(handle);
    holder.setActive(true);
    return holderRepository.save(holder);
  }
}
