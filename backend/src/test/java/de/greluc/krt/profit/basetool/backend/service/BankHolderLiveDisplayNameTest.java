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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankHolderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Pins the holder live-display-name resolution (REQ-BANK-003) against the real Testcontainers
 * PostgreSQL: every bank surface shows the linked user's <em>current</em> effective name — display
 * name preferred, username only as fallback — rather than the {@code handle} snapshot frozen at
 * registration, and falls back to the snapshot only once the user is deleted. Covers both the
 * interactive registry read ({@link BankHolderService#getHolders()}) and a historical surface (the
 * account statement PDF, fed by the live holder-leg projection), so the {@code CASE} resolution in
 * the JPQL projections and the entity-level {@link BankHolder#getDisplayName()} are exercised on a
 * real database, not just mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
class BankHolderLiveDisplayNameTest {

  @Autowired private BankHolderService bankHolderService;
  @Autowired private BankStatementReportService statementService;
  @Autowired private BankManagementReportService managementReportService;
  @Autowired private BankLedgerService bankLedgerService;
  @Autowired private UserRepository userRepository;
  @Autowired private BankHolderRepository holderRepository;
  @Autowired private BankAccountRepository accountRepository;

  @Test
  void getHolders_prefersLiveDisplayName_reflectsRenames_andFallsBackToSnapshotForDeletedUser() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);

    String aliceUsername = "alice-un-" + suffix;
    User alice = newUser(aliceUsername, null);
    BankHolder aliceHolder = newHolder(alice, alice.getEffectiveName());
    assertEquals(
        aliceUsername,
        handleOf(aliceHolder.getId()),
        "with no display name the registry shows the username");

    String aliceDisplay = "alice-disp-" + suffix;
    alice.setDisplayName(aliceDisplay);
    userRepository.save(alice);
    assertEquals(
        aliceDisplay,
        handleOf(aliceHolder.getId()),
        "the registry reflects the user's current display name, not the registration snapshot");

    User bob = newUser("bob-un-" + suffix, "bob-disp-" + suffix);
    BankHolder bobHolder = newHolder(bob, bob.getEffectiveName());
    assertEquals(
        "bob-disp-" + suffix,
        handleOf(bobHolder.getId()),
        "the display name is preferred over the username");

    String ghostSnapshot = "ghost-snap-" + suffix;
    BankHolder ghostHolder = newHolder(null, ghostSnapshot);
    assertEquals(
        ghostSnapshot,
        handleOf(ghostHolder.getId()),
        "a deleted user's holder falls back to the frozen handle snapshot");
  }

  @Test
  void statement_showsLiveDisplayName_notTheRegistrationSnapshot() throws IOException {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String carolUsername = "cu-" + suffix;
    String carolDisplay = "cd-" + suffix;
    User carol = newUser(carolUsername, null);
    BankHolder carolHolder = newHolder(carol, carol.getEffectiveName());
    carol.setDisplayName(carolDisplay);
    userRepository.save(carol);

    BankAccount account = newAccount("Live Name Konto " + UUID.randomUUID());
    bankLedgerService.bookDeposit(
        new BankDepositRequest(account.getId(), carolHolder.getId(), new BigDecimal("500"), null));

    String text =
        extractText(
            statementService.generateStatement(
                account.getId(),
                Instant.now().minus(1, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.HOURS),
                null));

    assertTrue(
        text.contains(carolDisplay), "the statement names the holder by their live display name");
    assertFalse(
        text.contains(carolUsername),
        "the statement no longer shows the registration-time username snapshot");
  }

  @Test
  void getHolder_detailHeader_usesLiveDisplayName_throughTheLazyUserLoad() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String username = "hu-" + suffix;
    String display = "hd-" + suffix;
    User user = newUser(username, null);
    BankHolder holder = newHolder(user, user.getEffectiveName());
    user.setDisplayName(display);
    userRepository.save(user);

    assertEquals(display, bankHolderService.getHolder(holder.getId()).handle());
  }

  @Test
  void managementReport_holderSection_showsLiveDisplayName() throws IOException {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String username = "ru-" + suffix;
    String display = "rd-" + suffix;
    User user = newUser(username, null);
    BankHolder holder = newHolder(user, user.getEffectiveName());
    user.setDisplayName(display);
    userRepository.save(user);

    BankAccount account = newAccount("Live Name Report Konto " + UUID.randomUUID());
    bankLedgerService.bookDeposit(
        new BankDepositRequest(account.getId(), holder.getId(), new BigDecimal("500"), null));

    String text = extractText(managementReportService.generateThreeMonthReport(null));

    assertTrue(
        text.contains(display), "the management report holder section shows the live display name");
    assertFalse(
        text.contains(username), "the management report does not show the username snapshot");
  }

  /** Resolves one holder's currently-shown display label through the registry read. */
  private String handleOf(UUID holderId) {
    return bankHolderService.getHolders().stream()
        .filter(h -> h.id().equals(holderId))
        .map(BankHolderDto::handle)
        .findFirst()
        .orElseThrow(() -> new AssertionError("holder " + holderId + " not in the registry"));
  }

  /** Persists a minimal active user with the given username and optional display name. */
  private User newUser(String username, String displayName) {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername(username);
    user.setDisplayName(displayName);
    User saved = userRepository.save(user);
    assertNotNull(saved.getId());
    return saved;
  }

  /** Persists an active holder optionally linked to a user, with the given handle snapshot. */
  private BankHolder newHolder(User user, String handleSnapshot) {
    BankHolder holder = new BankHolder();
    holder.setUser(user);
    holder.setHandle(handleSnapshot);
    holder.setActive(true);
    return holderRepository.save(holder);
  }

  /** Persists a fresh SPECIAL account so the statement has somewhere to book against. */
  private BankAccount newAccount(String name) {
    BankAccount account = new BankAccount();
    account.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    account.setName(name);
    account.setType(BankAccountType.SPECIAL);
    account.setStatus(BankAccountStatus.ACTIVE);
    return accountRepository.save(account);
  }

  /** Concatenates the text of every page of the generated PDF. */
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
}
