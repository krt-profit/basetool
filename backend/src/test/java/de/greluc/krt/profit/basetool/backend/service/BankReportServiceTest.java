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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankWithdrawalRequest;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for the bank PDF exports against real Postgres: the account statement
 * (REQ-BANK-014) and the three-month report (REQ-BANK-015).
 */
@SpringBootTest
@ActiveProfiles("test")
class BankReportServiceTest {

  @Autowired private BankStatementReportService statementService;
  @Autowired private BankManagementReportService managementReportService;
  @Autowired private BankLedgerService bankLedgerService;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private BankHolderRepository holderRepository;
  @Autowired private BankAuditEventRepository auditEventRepository;
  @Autowired private UserRepository userRepository;

  private BankAccount account;
  private BankHolder holder;
  private String holderHandle;

  /** Seeds a fresh account and holder per test (unique names per run). */
  @BeforeEach
  void seed() {
    account = newAccount("Report Konto " + UUID.randomUUID());
    holderHandle = "rh-" + UUID.randomUUID().toString().substring(0, 8);
    holder = newHolder(holderHandle);
  }

  @Test
  void statement_containsBalancesRunningColumnAndPerBookingHolder() throws IOException {
    Instant before = Instant.now().minus(1, ChronoUnit.HOURS);
    deposit("500");
    withdraw("200");
    Instant after = Instant.now().plus(1, ChronoUnit.HOURS);

    byte[] pdf = statementService.generateStatement(account.getId(), before, after, null);

    String text = extractText(pdf);
    assertTrue(text.contains("KONTOAUSZUG"), "title present");
    assertTrue(text.contains(account.getAccountNo()), "account number present");
    assertTrue(text.contains("ANFANGSSALDO"), "opening label present");
    assertTrue(text.contains("ENDSALDO"), "closing label present");
    assertTrue(text.contains("299 aUEC"), "closing balance 299 present (200 payout + 1 fee)");
    assertTrue(text.contains("+500"), "deposit amount present");
    assertTrue(text.contains("-201"), "withdrawal gross (200 + 1 fee) present");
    assertTrue(text.contains("Einzahlung"), "deposit type label present");
    assertTrue(text.contains("Auszahlung"), "withdrawal type label present");
    assertTrue(text.contains(holderHandle), "holder handle present on the booking rows");
    assertFalse(
        text.contains("HALTER-VERTEILUNG ZUM STICHTAG"),
        "the per-account holder distribution section is gone (ADR-0039)");
    assertTrue(
        text.contains("Generiert von Profit Basetool am ") && text.contains(" UTC"),
        "footer carries the UTC generation timestamp");
  }

  @Test
  void statement_appliesPeriodFilterToBothDirections() throws IOException, InterruptedException {
    Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
    deposit("500");
    Thread.sleep(75);
    Instant mid = Instant.now();
    Thread.sleep(75);
    withdraw("200");
    Instant end = Instant.now().plus(1, ChronoUnit.HOURS);

    String firstHalf =
        extractText(statementService.generateStatement(account.getId(), start, mid, null));
    String secondHalf =
        extractText(statementService.generateStatement(account.getId(), mid, end, null));

    assertTrue(firstHalf.contains("+500"), "deposit inside first period");
    assertFalse(firstHalf.contains("Auszahlung"), "withdrawal outside first period");
    assertTrue(firstHalf.contains("500 aUEC"), "closing 500 in first period");
    assertFalse(secondHalf.contains("+500"), "deposit outside second period");
    assertTrue(secondHalf.contains("-201"), "withdrawal gross inside second period");
    assertTrue(secondHalf.contains("299 aUEC"), "closing 299 in second period");
  }

  @Test
  void statement_emptyPeriodShowsEmptyRowAndZeroMovement() throws IOException {
    deposit("500");
    Instant farPast = Instant.now().minus(48, ChronoUnit.HOURS);
    Instant past = Instant.now().minus(24, ChronoUnit.HOURS);

    String text =
        extractText(statementService.generateStatement(account.getId(), farPast, past, null));

    assertTrue(text.contains("Keine Buchungen im Zeitraum."), "empty row present");
    assertFalse(text.contains("+500"), "no booking row leaks into the period");
  }

  @Test
  void statement_recordsOneAuditEventPerExport() {
    deposit("100");
    long before = auditEventRepository.count();

    statementService.generateStatement(
        account.getId(),
        Instant.now().minus(1, ChronoUnit.HOURS),
        Instant.now().plus(1, ChronoUnit.HOURS),
        null);

    assertEquals(before + 1, auditEventRepository.count(), "exactly one STATEMENT_EXPORTED row");
  }

  @Test
  void statement_redactedVariant_omitsHolderColumnButKeepsHistory() throws IOException {
    Instant before = Instant.now().minus(1, ChronoUnit.HOURS);
    deposit("500");
    withdraw("200");
    Instant after = Instant.now().plus(1, ChronoUnit.HOURS);

    String full =
        extractText(statementService.generateStatement(account.getId(), before, after, null));
    String redacted =
        extractText(statementService.generateStatement(account.getId(), before, after, null, true));

    assertTrue(full.contains(holderHandle), "full statement keeps the holder column");
    assertFalse(redacted.contains(holderHandle), "redacted statement hides the holder handle");
    assertFalse(redacted.contains("HALTER"), "redacted statement drops the Halter column header");
    assertTrue(redacted.contains("+500"), "deposit amount still present when redacted");
    assertTrue(redacted.contains("-201"), "withdrawal gross still present when redacted");
    assertTrue(redacted.contains("299 aUEC"), "closing balance still present when redacted");
  }

  @Test
  void statement_showsCounterpartyOnQuellZielkontoColumnForBothVariants() throws IOException {
    Instant before = Instant.now().minus(1, ChronoUnit.HOURS);
    String counterpartyHandle = "cp-" + UUID.randomUUID().toString().substring(0, 8);
    User counterparty = newUser(counterpartyHandle);
    bankLedgerService.bookDeposit(
        new BankDepositRequest(
            account.getId(),
            holder.getId(),
            new BigDecimal("500"),
            null,
            counterparty.getId(),
            null));
    Instant after = Instant.now().plus(1, ChronoUnit.HOURS);

    String full =
        extractText(statementService.generateStatement(account.getId(), before, after, null));
    String redacted =
        extractText(statementService.generateStatement(account.getId(), before, after, null, true));

    assertTrue(
        full.contains("QUELL-/ZIELKONTO"),
        "full statement carries the Quell-/Zielkonto column header");
    assertTrue(full.contains(counterpartyHandle), "full statement names the counterparty");
    assertTrue(
        redacted.contains("QUELL-/ZIELKONTO"),
        "redacted statement keeps the Quell-/Zielkonto column");
    assertTrue(
        redacted.contains(counterpartyHandle),
        "redacted statement still shows the counterparty (REQ-BANK-044 amended)");
    assertFalse(
        redacted.contains(holderHandle), "redacted statement still hides the aUEC-custody Halter");
  }

  @Test
  void statement_rendersReasonAndNoteInDetailSubRow() throws IOException {
    Instant before = Instant.now().minus(1, ChronoUnit.HOURS);
    deposit("500");
    String reason = "reason-" + UUID.randomUUID().toString().substring(0, 8);
    String note = "note-" + UUID.randomUUID().toString().substring(0, 8);
    bankLedgerService.bookWithdrawal(
        new BankWithdrawalRequest(
            account.getId(),
            holder.getId(),
            new BigDecimal("100"),
            note,
            reason,
            null,
            null,
            false));
    Instant after = Instant.now().plus(1, ChronoUnit.HOURS);

    String text =
        extractText(statementService.generateStatement(account.getId(), before, after, null));

    assertTrue(text.contains(reason), "the Begründung renders in the sub-row");
    assertTrue(text.contains(note), "the Notiz renders in the sub-row");
    assertTrue(text.contains("Notiz"), "the sub-row Notiz label renders");
    assertTrue(
        text.indexOf(reason) < text.indexOf(note),
        "the Begründung is rendered before the Notiz (it is the more important field)");
  }

  @Test
  void statement_rejectsInvertedPeriodAndUnknownAccount() {
    Instant now = Instant.now();

    assertThrows(
        BadRequestException.class,
        () ->
            statementService.generateStatement(
                account.getId(), now, now.minus(1, ChronoUnit.HOURS), null));
    assertThrows(
        NotFoundException.class,
        () ->
            statementService.generateStatement(
                UUID.randomUUID(), now.minus(1, ChronoUnit.HOURS), now, null));
  }

  @Test
  void threeMonthReport_containsAccountSummariesAndAuditEvent() throws IOException {
    deposit("750");
    long auditBefore = auditEventRepository.count();

    byte[] pdf = managementReportService.generateThreeMonthReport(null);

    String text = extractText(pdf);
    assertTrue(text.contains("3-MONATS-REPORT"), "title present");
    assertTrue(text.contains(account.getAccountNo()), "seeded account section present");
    assertTrue(text.contains("Anfangssaldo"), "summary opening label present");
    assertTrue(text.contains("Eingänge"), "summary inflow label present");
    assertTrue(text.contains("Endsaldo"), "summary closing label present");
    assertTrue(text.contains("+750"), "itemized booking present");
    assertTrue(
        text.contains("HALTERBESTAND GESAMT"), "global holder-balance section present (ADR-0039)");
    assertTrue(text.contains(holderHandle), "the global holder section names the holder");
    assertTrue(text.contains("Kontostandverlauf"), "per-account balance chart caption present");
    assertEquals(
        auditBefore + 1, auditEventRepository.count(), "exactly one MANAGEMENT_REPORT_EXPORTED");
  }

  @Test
  void threeMonthReport_startsEachAccountOnItsOwnPage() throws IOException {
    BankAccount second = newAccount("Report Konto 2 " + UUID.randomUUID());
    bankLedgerService.bookDeposit(
        new BankDepositRequest(second.getId(), holder.getId(), new BigDecimal("321"), null));
    deposit("750");

    byte[] pdf = managementReportService.generateThreeMonthReport(null);

    try (PdfReader reader = new PdfReader(pdf)) {
      PdfTextExtractor extractor = new PdfTextExtractor(reader);
      int firstAccountPage = -1;
      int secondAccountPage = -1;
      for (int i = 1; i <= reader.getNumberOfPages(); i++) {
        String page = extractor.getTextFromPage(i);
        if (page.contains(account.getAccountNo())) {
          firstAccountPage = i;
        }
        if (page.contains(second.getAccountNo())) {
          secondAccountPage = i;
        }
      }
      assertTrue(firstAccountPage > 0 && secondAccountPage > 0, "both account sections present");
      assertNotEquals(
          firstAccountPage,
          secondAccountPage,
          "each account section starts on its own page (no shared page)");
    }
  }

  /**
   * Extracts the text of every page of the given PDF.
   *
   * @param pdf the PDF bytes
   * @return the concatenated page texts
   * @throws IOException when the PDF cannot be parsed
   */
  private static String extractText(byte[] pdf) throws IOException {
    assertNotNull(pdf);
    assertTrue(pdf.length > 0, "PDF must not be empty");
    PdfReader reader = new PdfReader(pdf);
    try {
      PdfTextExtractor extractor = new PdfTextExtractor(reader);
      StringBuilder sb = new StringBuilder();
      for (int i = 1; i <= reader.getNumberOfPages(); i++) {
        sb.append(extractor.getTextFromPage(i)).append('\n');
      }
      return sb.toString();
    } finally {
      reader.close();
    }
  }

  private void deposit(String amount) {
    bankLedgerService.bookDeposit(
        new BankDepositRequest(account.getId(), holder.getId(), new BigDecimal(amount), null));
  }

  private void withdraw(String amount) {
    bankLedgerService.bookWithdrawal(
        new BankWithdrawalRequest(account.getId(), holder.getId(), new BigDecimal(amount), null));
  }

  /** Creates a minimal persisted tool user; its username doubles as the effective-name handle. */
  private User newUser(String handle) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(handle);
    user.setRank(1);
    user.setInKeycloak(true);
    return userRepository.save(user);
  }

  private BankAccount newAccount(String name) {
    BankAccount a = new BankAccount();
    a.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    a.setName(name);
    a.setType(BankAccountType.AREA);
    a.setAreaName(name);
    a.setStatus(BankAccountStatus.ACTIVE);
    BankAccount saved = accountRepository.save(a);
    assertNotNull(saved.getId());
    return saved;
  }

  private BankHolder newHolder(String handle) {
    BankHolder h = new BankHolder();
    h.setHandle(handle);
    h.setActive(true);
    return holderRepository.save(h);
  }
}
