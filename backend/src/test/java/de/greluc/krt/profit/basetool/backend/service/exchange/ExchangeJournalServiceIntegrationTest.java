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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeJournalAction;
import de.greluc.krt.profit.basetool.backend.model.ExchangeJournalEntry;
import de.greluc.krt.profit.basetool.backend.model.ExchangeResource;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeJournalRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The exchange write journal records in the write's own transaction, counts a client's removals in
 * a window, lists what an undo walks, and purges past its retention (REQ-XCH-021, REQ-XCH-022).
 */
@SpringBootTest
class ExchangeJournalServiceIntegrationTest {

  private static final String KEY = "Kx9_" + "j".repeat(39);

  @Autowired private ExchangeJournalService journalService;
  @Autowired private ExchangeJournalRepository journalRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;

  private ExchangeCaller caller;

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("journal-" + UUID.randomUUID());
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    caller = new ExchangeCaller(userRepository.saveAndFlush(user).getId(), "versekit", KEY);
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM app_user WHERE id = ?", caller.member());
  }

  @Test
  void aWriteIsJournaledWithItsStatesAndTransaction() {
    UUID batch = UUID.randomUUID();

    inTransaction(
        () ->
            journalService.record(
                caller,
                batch,
                ExchangeJournalAction.BLUEPRINT_REMOVE,
                "arrowhead",
                true,
                Map.of("productKey", "arrowhead", "note", "from Orison"),
                null));

    ExchangeJournalEntry entry = entries().getFirst();
    assertThat(entry.getResource()).isEqualTo(ExchangeResource.BLUEPRINT);
    assertThat(entry.getClientId()).isEqualTo("versekit");
    assertThat(entry.getInstallationKey()).isEqualTo(KEY);
    assertThat(entry.getBatchId()).isEqualTo(batch);
    assertThat(entry.isRemoval()).isTrue();
    assertThat(entry.getBeforeState()).contains("\"note\":\"from Orison\"");
    assertThat(entry.getAfterState()).isNull();
    assertThat(entry.getTx()).isPositive();
    assertThat(entry.getRecordedAt()).isNotNull();
  }

  @Test
  void theJournalRollsBackWithTheWrite() {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.executeWithoutResult(
        status -> {
          add("rolled-back");
          status.setRollbackOnly();
        });

    assertThat(entries()).isEmpty();
  }

  @Test
  void aJournalEntryOutsideAWriteIsRefused() {
    assertThatThrownBy(
            () ->
                journalService.record(
                    caller,
                    UUID.randomUUID(),
                    ExchangeJournalAction.BLUEPRINT_ADD,
                    "x",
                    false,
                    null,
                    Map.of()))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void onlyTheClientsLiveRemovalsWithinTheWindowCount() {
    inTransaction(
        () -> {
          remove("one");
          remove("two");
          remove("undone");
          remove("old");
          add("added");
        });
    jdbc.update(
        "UPDATE exchange_journal SET undone_at = now() WHERE user_id = ? AND entity_key = 'undone'",
        caller.member());
    jdbc.update(
        "UPDATE exchange_journal SET recorded_at = now() - interval '2 days'"
            + " WHERE user_id = ? AND entity_key = 'old'",
        caller.member());
    Instant since = Instant.now().minus(Duration.ofHours(24));

    assertThat(journalService.removalsSince(caller, ExchangeResource.BLUEPRINT, since))
        .isEqualTo(2);
    assertThat(journalService.removalsSince(caller, ExchangeResource.SHIP, since)).isZero();
    assertThat(
            journalService.removalsSince(
                new ExchangeCaller(caller.member(), "other-client", KEY),
                ExchangeResource.BLUEPRINT,
                since))
        .isZero();
  }

  @Test
  void anUndoWalksTheWritesNewestFirstAndSkipsUndoneOnes() {
    inTransaction(() -> add("first"));
    inTransaction(() -> add("second"));
    inTransaction(() -> add("undone"));
    jdbc.update(
        "UPDATE exchange_journal SET undone_at = now() WHERE user_id = ? AND entity_key = 'undone'",
        caller.member());

    List<ExchangeJournalEntry> undoable =
        journalRepository.findUndoable(
            caller.member(), caller.clientId(), Instant.now().minus(Duration.ofHours(1)));

    assertThat(undoable)
        .extracting(ExchangeJournalEntry::getEntityKey)
        .containsExactly("second", "first");
  }

  @Test
  void writesPastTheRetentionArePurged() {
    inTransaction(
        () -> {
          add("kept");
          add("expired");
        });
    jdbc.update(
        "UPDATE exchange_journal SET recorded_at = now() - interval '100 days'"
            + " WHERE user_id = ? AND entity_key = 'expired'",
        caller.member());

    journalService.purgeRecordedBefore(Instant.now().minus(Duration.ofDays(90)));

    assertThat(entries()).extracting(ExchangeJournalEntry::getEntityKey).containsExactly("kept");
  }

  /**
   * Journals a blueprint add.
   *
   * @param key the product key
   */
  private void add(@NotNull String key) {
    journalService.record(
        caller,
        UUID.randomUUID(),
        ExchangeJournalAction.BLUEPRINT_ADD,
        key,
        false,
        null,
        Map.of("productKey", key));
  }

  /**
   * Journals a blueprint removal.
   *
   * @param key the product key
   */
  private void remove(@NotNull String key) {
    journalService.record(
        caller,
        UUID.randomUUID(),
        ExchangeJournalAction.BLUEPRINT_REMOVE,
        key,
        true,
        Map.of("productKey", key),
        null);
  }

  /**
   * Runs work in one committed transaction.
   *
   * @param work the work
   */
  private void inTransaction(@NotNull Runnable work) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
  }

  /**
   * Lists the member's journal entries.
   *
   * @return the entries in the order they were written
   */
  private @NotNull List<ExchangeJournalEntry> entries() {
    return journalRepository.findAllByUserIdOrderByRecordedAtAsc(caller.member());
  }
}
