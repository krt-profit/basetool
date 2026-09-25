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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEvent;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.dto.BankAuditEventDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for the audit-log query against the real Testcontainers PostgreSQL — the path
 * the mocked {@code BankAuditServiceTest} cannot cover. Pins that {@code findFiltered} runs without
 * a "could not determine data type of parameter" failure when filters are absent (all-null), the
 * exact call the admin viewer and the e2e suite make; the {@code (CAST(:param AS type) IS NULL OR
 * ...)} pattern is what makes that work on PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("test")
class BankAuditQueryIntegrationTest {

  @Autowired private BankAuditService bankAuditService;
  @Autowired private BankLedgerService bankLedgerService;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private BankHolderRepository holderRepository;
  @Autowired private BankAuditEventRepository auditEventRepository;

  @Test
  void getEvents_withAllNullFilters_runsAndReturnsAPage() {
    seedDepositAuditRow();
    PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));

    Page<BankAuditEventDto> page =
        bankAuditService.getEvents(null, null, null, null, null, null, pageable);

    assertNotNull(page);
    assertTrue(page.getTotalElements() >= 1, "the seeded deposit produced an audit row");
  }

  @Test
  void getEvents_withEveryFilterSet_runsWithoutTypeInferenceError() {
    seedDepositAuditRow();
    PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));

    Page<BankAuditEventDto> page =
        bankAuditService.getEvents(
            Instant.now().minus(1, ChronoUnit.DAYS),
            Instant.now().plus(1, ChronoUnit.DAYS),
            UUID.randomUUID(),
            UUID.randomUUID(),
            BankAuditEventType.DEPOSIT_BOOKED,
            "basetool-android",
            pageable);

    assertNotNull(page);
  }

  @Test
  void getEvents_clientFilter_selectsOnlyThatClientsRows() {
    seedAuditRowFrom("basetool-frontend");
    seedAuditRowFrom("basetool-android");
    PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));

    Page<BankAuditEventDto> fromApp =
        bankAuditService.getEvents(null, null, null, null, null, "basetool-android", pageable);

    assertEquals(1, fromApp.getTotalElements());
    assertEquals("basetool-android", fromApp.getContent().getFirst().clientId());
  }

  @Test
  void getEvents_blankClientFilter_meansNoFilterRatherThanNoMatches() {
    seedAuditRowFrom("basetool-frontend");
    PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "occurredAt"));

    Page<BankAuditEventDto> unfiltered =
        bankAuditService.getEvents(null, null, null, null, null, "  ", pageable);

    assertTrue(unfiltered.getTotalElements() >= 1, "a blank client filter must not exclude rows");
  }

  /**
   * Persists one account-less bank audit row attributed to the given client.
   *
   * <p>Written straight through the repository rather than by booking a deposit: the point is two
   * rows that differ in exactly one field, and a real booking would take its client from the test's
   * own (absent) security context for both.
   *
   * @param clientId the bounded client label
   */
  private void seedAuditRowFrom(String clientId) {
    auditEventRepository.save(
        BankAuditEvent.builder()
            .occurredAt(Instant.now())
            .actorHandle("integration-test")
            .eventType(BankAuditEventType.AUDIT_LOG_EXPORTED)
            .details("format=json")
            .clientId(clientId)
            .build());
  }

  private void seedDepositAuditRow() {
    BankAccount account = new BankAccount();
    account.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    account.setName("Audit Query Konto " + UUID.randomUUID());
    account.setType(BankAccountType.SPECIAL);
    account.setStatus(BankAccountStatus.ACTIVE);
    account = accountRepository.save(account);

    BankHolder holder = new BankHolder();
    holder.setHandle("audit-query-holder-" + UUID.randomUUID());
    holder.setActive(true);
    holder = holderRepository.save(holder);

    bankLedgerService.bookDeposit(
        new BankDepositRequest(account.getId(), holder.getId(), new BigDecimal("100"), null));
  }
}
