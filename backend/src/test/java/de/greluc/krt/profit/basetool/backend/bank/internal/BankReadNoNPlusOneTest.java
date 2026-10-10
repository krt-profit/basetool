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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Verifies against real Postgres that the bank dashboard and paged account list issue a fixed
 * number of SQL statements regardless of account count (REQ-BANK-020, REQ-DATA-003).
 */
@SpringBootTest
class BankReadNoNPlusOneTest {

  /** Account fan-out for the seed — comfortably past the REQ-BANK-020 ≥ 100 threshold. */
  private static final int ACCOUNTS = 120;

  /**
   * Upper bound on statements per read. The grouped reads use ~3 statements (list + balances +
   * slices / count); the generous ceiling still catches a per-account N+1 (which would be ≥ {@value
   * #ACCOUNTS}).
   */
  private static final int STATEMENT_BOUND = 10;

  /** Granted accounts, one pending request each, for the queue case. */
  private static final int QUEUE_ACCOUNTS = 40;

  @Autowired private BankBookingRequestService bankBookingRequestService;
  @Autowired private BankAccountGrantRepository grantRepository;
  @Autowired private BankBookingRequestRepository requestRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private BankDashboardService bankDashboardService;
  @Autowired private BankAccountService bankAccountService;
  @Autowired private BankLedgerService bankLedgerService;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private BankHolderRepository holderRepository;
  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  void dashboardAndAccountListStayStatementBounded_independentOfAccountCount() {
    BankHolder holder = newHolder("vol-holder-" + UUID.randomUUID());
    UUID managerId = UUID.randomUUID();
    for (int i = 0; i < ACCOUNTS; i++) {
      BankAccount account = newAccount("Vol Konto " + i + " " + UUID.randomUUID());
      bankLedgerService.bookDeposit(
          new BankDepositRequest(account.getId(), holder.getId(), new BigDecimal("100"), null));
    }
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);

    stats.clear();
    bankDashboardService.getDashboard(true, managerId);
    long dashboardStatements = stats.getPrepareStatementCount();

    stats.clear();
    bankAccountService.getAccounts(
        true,
        managerId,
        null,
        java.util.EnumSet.allOf(BankAccountStatus.class),
        java.util.EnumSet.allOf(BankAccountType.class),
        PageRequest.of(0, 50));
    long listStatements = stats.getPrepareStatementCount();

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

  /**
   * The staff queue judges every row's confirm action for an employee with one grant read, so its
   * statement count does not grow with the rows, and each row carries the confirm endpoint's answer
   * (REQ-BANK-023).
   */
  @Test
  void requestQueueMarksConfirmCapabilityStatementBounded_independentOfRowCount() {
    User employee = newUser("queue-emp-" + UUID.randomUUID());
    List<BankAccount> depositOnly = new ArrayList<>();
    for (int i = 0; i < QUEUE_ACCOUNTS; i++) {
      BankAccount account = newAccount("Queue Konto " + i + " " + UUID.randomUUID());
      boolean deposit = i % 2 == 0;
      grant(employee, account, deposit, !deposit);
      if (deposit) {
        depositOnly.add(account);
      }
      newPendingDeposit(account);
    }
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    JwtAuthenticationToken authentication =
        new JwtAuthenticationToken(
            Jwt.withTokenValue("queue-test")
                .header("alg", "none")
                .subject(employee.getId().toString())
                .build(),
            List.of(new SimpleGrantedAuthority("ROLE_BANK_EMPLOYEE")));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    try {
      stats.clear();
      Page<BankBookingRequestDto> queue =
          bankBookingRequestService.listQueue(
              Set.of(BankBookingRequestStatus.PENDING),
              PageRequest.of(0, QUEUE_ACCOUNTS),
              authentication);
      long queueStatements = stats.getPrepareStatementCount();

      assertEquals(QUEUE_ACCOUNTS, queue.getContent().size());
      Set<UUID> depositAccountIds =
          depositOnly.stream().map(BankAccount::getId).collect(Collectors.toSet());
      for (BankBookingRequestDto row : queue.getContent()) {
        assertEquals(depositAccountIds.contains(row.accountId()), row.callerMayConfirm());
      }
      assertTrue(
          queueStatements <= STATEMENT_BOUND,
          () -> "request queue issued " + queueStatements + " statements (suspected N+1)");
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  /** Persists a minimal user, the grant's {@code @MapsId} half. */
  private User newUser(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    return userRepository.save(user);
  }

  /** Grants the user a view row on the account with the given deposit and withdraw flags. */
  private void grant(User user, BankAccount account, boolean deposit, boolean withdraw) {
    BankAccountGrant grant = new BankAccountGrant();
    grant.setId(new BankAccountGrantId(user.getId(), account.getId()));
    grant.setUser(user);
    grant.setAccount(account);
    grant.setCanDeposit(deposit);
    grant.setCanWithdraw(withdraw);
    grantRepository.save(grant);
  }

  /** Persists a pending deposit request on the account. */
  private void newPendingDeposit(BankAccount account) {
    BankBookingRequest request = new BankBookingRequest();
    request.setAccount(account);
    request.setType(BankBookingRequestType.DEPOSIT);
    request.setAmount(new BigDecimal("100"));
    request.setStatus(BankBookingRequestStatus.PENDING);
    request.setRequesterHandle("requester");
    requestRepository.save(request);
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
