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

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Pins the no-N+1 contract of the bank read surface (REQ-BANK-020, REQ-DATA-003) against the real
 * Testcontainers PostgreSQL: with ≥ 100 accounts seeded, the management dashboard and the paged
 * account list each issue a fixed handful of SQL statements — the count is bounded by the grouped
 * queries and does <em>not</em> grow with the account count (the property an N+1 would violate
 * regardless of total posting volume).
 */
@SpringBootTest
@ActiveProfiles("test")
class BankReadNoNPlusOneTest {

  /** Account fan-out for the seed — comfortably past the REQ-BANK-020 ≥ 100 threshold. */
  private static final int ACCOUNTS = 120;

  /**
   * Upper bound on statements per read. The grouped reads use ~3 statements (list + balances +
   * slices / count); the generous ceiling still catches a per-account N+1 (which would be ≥ {@value
   * #ACCOUNTS}).
   */
  private static final int STATEMENT_BOUND = 10;

  @Autowired private BankDashboardService bankDashboardService;
  @Autowired private BankAccountService bankAccountService;
  @Autowired private BankLedgerService bankLedgerService;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private BankHolderRepository holderRepository;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  void dashboardAndAccountListStayStatementBounded_independentOfAccountCount() {
    // Given: many accounts, each with a posting (an N+1 read would scale with the account count)
    BankHolder holder = newHolder("vol-holder-" + UUID.randomUUID());
    UUID managerId = UUID.randomUUID();
    for (int i = 0; i < ACCOUNTS; i++) {
      BankAccount account = newAccount("Vol Konto " + i + " " + UUID.randomUUID());
      bankLedgerService.bookDeposit(
          new BankDepositRequest(account.getId(), holder.getId(), new BigDecimal("100"), null));
    }
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);

    // When: the management dashboard (every account) ...
    stats.clear();
    bankDashboardService.getDashboard(true, managerId);
    long dashboardStatements = stats.getPrepareStatementCount();

    // ... and the paged management account list (no text/status/type filter — the full enum sets)
    stats.clear();
    bankAccountService.getAccounts(
        true,
        managerId,
        null,
        java.util.EnumSet.allOf(BankAccountStatus.class),
        java.util.EnumSet.allOf(BankAccountType.class),
        PageRequest.of(0, 50));
    long listStatements = stats.getPrepareStatementCount();

    // Then: a fixed handful of statements despite the 120 accounts (no per-account N+1)
    assertTrue(
        dashboardStatements <= STATEMENT_BOUND,
        () ->
            "dashboard issued "
                + dashboardStatements
                + " statements for "
                + ACCOUNTS
                + " accounts (suspected N+1)");
    assertTrue(
        listStatements <= STATEMENT_BOUND,
        () -> "account list issued " + listStatements + " statements (suspected N+1)");
  }

  /** Persists a fresh SPECIAL account (no lazy org-unit association to confound the count). */
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
