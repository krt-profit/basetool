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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountGrant;
import de.greluc.krt.profit.basetool.backend.model.BankAccountGrantId;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAccountDto;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the server-side bank-account search that backs the remote account pickers and the paged
 * management table (REQ-BANK-053, ADR-0104): the case-insensitive name/account-number substring
 * filter, the status and type narrowing, the grant-scoped variant, and that the bound-parameter
 * query is SQL-injection-safe. Runs against the real Testcontainers PostgreSQL so the {@code
 * LOWER(...) LIKE LOWER(CONCAT('%', :query, '%'))} predicate behaves exactly as in production.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BankAccountSearchTest {

  private static final Set<BankAccountStatus> ALL_STATUSES = EnumSet.allOf(BankAccountStatus.class);
  private static final Set<BankAccountType> ALL_TYPES = EnumSet.allOf(BankAccountType.class);
  private static final PageRequest FIRST_PAGE =
      PageRequest.of(0, 50, Sort.by("accountNo").ascending());

  @Autowired private BankAccountService bankAccountService;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private BankAccountGrantRepository grantRepository;
  @Autowired private UserRepository userRepository;

  @Test
  void managementSearch_filtersByNameSubstring_caseInsensitive() {
    // Given three distinctly-named SPECIAL accounts
    BankAccount phoenix = newAccount("Staffel PHOENIX", BankAccountType.SPECIAL);
    newAccount("Staffel IRIDIUM", BankAccountType.SPECIAL);
    newAccount("Sonderkonto Logistik", BankAccountType.SPECIAL);

    // When searching by a lower-cased name fragment
    Page<BankAccountDto> result =
        bankAccountService.getAccounts(
            true, UUID.randomUUID(), "phoenix", ALL_STATUSES, ALL_TYPES, FIRST_PAGE);

    // Then only the matching account comes back
    assertThat(result.getContent()).extracting(BankAccountDto::id).containsExactly(phoenix.getId());
  }

  @Test
  void managementSearch_matchesAccountNumber() {
    // Given an account whose number we can search for
    BankAccount account = newAccount("Any Name", BankAccountType.SPECIAL);
    String accountNo = account.getAccountNo();

    // When searching by the account number
    Page<BankAccountDto> result =
        bankAccountService.getAccounts(
            true, UUID.randomUUID(), accountNo, ALL_STATUSES, ALL_TYPES, FIRST_PAGE);

    // Then the account is found by its number, not just its name
    assertThat(result.getContent()).extracting(BankAccountDto::id).contains(account.getId());
  }

  @Test
  void managementSearch_statusFilter_excludesClosed() {
    // Given one active and one closed account sharing a search term
    BankAccount active = newAccount("Filterprobe Aktiv", BankAccountType.SPECIAL);
    BankAccount closed = newAccount("Filterprobe Zu", BankAccountType.SPECIAL);
    closed.setStatus(BankAccountStatus.CLOSED);
    accountRepository.save(closed);

    // When searching ACTIVE-only (the picker path)
    Page<BankAccountDto> result =
        bankAccountService.getAccounts(
            true,
            UUID.randomUUID(),
            "Filterprobe",
            EnumSet.of(BankAccountStatus.ACTIVE),
            ALL_TYPES,
            FIRST_PAGE);

    // Then the closed account is excluded
    assertThat(result.getContent()).extracting(BankAccountDto::id).containsExactly(active.getId());
  }

  @Test
  void managementSearch_typeFilter_narrowsToRequestedType() {
    // Given a CARTEL and a SPECIAL account
    BankAccount cartel = newAccount("Typprobe Kartell", BankAccountType.CARTEL);
    newAccount("Typprobe Sonder", BankAccountType.SPECIAL);

    // When narrowing to CARTEL (the singleton lookup path)
    Page<BankAccountDto> result =
        bankAccountService.getAccounts(
            true,
            UUID.randomUUID(),
            "Typprobe",
            ALL_STATUSES,
            EnumSet.of(BankAccountType.CARTEL),
            FIRST_PAGE);

    // Then only the CARTEL account matches
    assertThat(result.getContent()).extracting(BankAccountDto::id).containsExactly(cartel.getId());
  }

  @Test
  void search_isInjectionSafe_andTreatsWildcardsAsHarmlessLikeWildcards() {
    // The query is a bound parameter, so it can never break out of the LIKE; a caller-supplied '%'
    // acts as a harmless LIKE wildcard on this bank-employee-gated read (it is intentionally not
    // LikePatterns-escaped — plain LIKE does not honour the backslash escape here). A distinctive
    // token still narrows the result; an SQL-injection attempt is treated as literal text.
    BankAccount phoenix =
        newAccount("Phoenix Reserve " + UUID.randomUUID(), BankAccountType.SPECIAL);

    // A wildcard-bearing query still finds the matching account (does not error out).
    Page<BankAccountDto> wildcard =
        bankAccountService.getAccounts(
            true, UUID.randomUUID(), "Phoenix%Reserve", ALL_STATUSES, ALL_TYPES, FIRST_PAGE);
    assertThat(wildcard.getContent()).extracting(BankAccountDto::id).contains(phoenix.getId());

    // A crafted injection string is bound as literal text and simply matches nothing (no error).
    Page<BankAccountDto> injection =
        bankAccountService.getAccounts(
            true,
            UUID.randomUUID(),
            "'; DROP TABLE bank_account; --",
            ALL_STATUSES,
            ALL_TYPES,
            FIRST_PAGE);
    assertThat(injection.getContent())
        .extracting(BankAccountDto::id)
        .doesNotContain(phoenix.getId());
  }

  @Test
  void employeeSearch_scopesToGrantedAccounts() {
    // Given an employee granted exactly one of two matching accounts
    User employee = newUser("search-emp-" + UUID.randomUUID());
    BankAccount granted = newAccount("Grantprobe A", BankAccountType.SPECIAL);
    BankAccount ungranted = newAccount("Grantprobe B", BankAccountType.SPECIAL);
    grant(employee, granted);

    // When the employee (non-management) searches the shared term
    Page<BankAccountDto> result =
        bankAccountService.getAccounts(
            false, employee.getId(), "Grantprobe", ALL_STATUSES, ALL_TYPES, FIRST_PAGE);

    // Then only the granted account is visible; the ungranted one is filtered out
    assertThat(result.getContent()).extracting(BankAccountDto::id).containsExactly(granted.getId());
    assertThat(result.getContent())
        .extracting(BankAccountDto::id)
        .doesNotContain(ungranted.getId());
  }

  @Test
  void blankQuery_dropsTheTextFilter() {
    // Given a couple of accounts
    BankAccount a = newAccount("Leerprobe Eins " + UUID.randomUUID(), BankAccountType.SPECIAL);
    BankAccount b = newAccount("Leerprobe Zwei " + UUID.randomUUID(), BankAccountType.SPECIAL);

    // When the query is blank (the browse-mode empty fetch)
    Page<BankAccountDto> result =
        bankAccountService.getAccounts(
            true, UUID.randomUUID(), "   ", ALL_STATUSES, ALL_TYPES, FIRST_PAGE);

    // Then both accounts are within the unfiltered result
    assertThat(result.getContent()).extracting(BankAccountDto::id).contains(a.getId(), b.getId());
  }

  /** Persists a fresh account of the given type with a server-drawn account number. */
  private BankAccount newAccount(String name, BankAccountType type) {
    BankAccount account = new BankAccount();
    account.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    account.setName(name);
    account.setType(type);
    account.setStatus(BankAccountStatus.ACTIVE);
    return accountRepository.save(account);
  }

  /** Persists a minimal user (the grant's {@code @MapsId} half). */
  private User newUser(String username) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    return userRepository.save(user);
  }

  /** Grants the user full booking capability on the account (row existence = view access). */
  private void grant(User user, BankAccount account) {
    BankAccountGrant grant = new BankAccountGrant();
    grant.setId(new BankAccountGrantId(user.getId(), account.getId()));
    grant.setUser(user);
    grant.setAccount(account);
    grant.setCanDeposit(true);
    grant.setCanWithdraw(true);
    grant.setCanTransfer(true);
    grantRepository.save(grant);
  }
}
