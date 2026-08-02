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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link UserSyncService} — the Keycloak-&gt;local reconciliation shared by the
 * scheduled {@link de.greluc.krt.profit.basetool.backend.task.UserSyncTask} and the admin-triggered
 * manual sync endpoint.
 */
@ExtendWith(MockitoExtension.class)
class UserSyncServiceTest {

  @Mock private KeycloakService keycloakService;

  @Mock private UserReconciliationService userReconciliationService;

  @Mock private BankHolderReconciliationService bankHolderReconciliationService;

  @InjectMocks private UserSyncService userSyncService;

  @Test
  void syncFromKeycloak_fetchesUpsertsReconcilesAndReturnsTheSyncedCount() {
    KeycloakUserDto user1 = user("user1");
    KeycloakUserDto user2 = user("user2");
    when(keycloakService.fetchUsers(anyCollection(), anySet())).thenReturn(List.of(user1, user2));

    int count = userSyncService.syncFromKeycloak();

    assertEquals(2, count);
    verify(userReconciliationService).syncUser(user1);
    verify(userReconciliationService).syncUser(user2);
    verify(userReconciliationService).markMissingUsers(anySet());
    verify(bankHolderReconciliationService).reconcileAll();
  }

  @Test
  void syncFromKeycloak_onEmptyRoster_skipsWithoutTouchingLocalState() {
    when(keycloakService.fetchUsers(anyCollection(), anySet())).thenReturn(Collections.emptyList());

    int count = userSyncService.syncFromKeycloak();

    assertEquals(0, count);
    verify(keycloakService).fetchUsers(anyCollection(), anySet());
    // The role-name + already-linked reads happen before the fetch (to parameterise it), but an
    // empty roster must touch no write path and never reconcile the bank holders.
    verify(userReconciliationService, never()).syncUser(any(KeycloakUserDto.class));
    verify(userReconciliationService, never()).markMissingUsers(anySet());
    verifyNoInteractions(bankHolderReconciliationService);
  }

  @Test
  void syncFromKeycloak_continuesAndCountsOnlyTheUsersThatSucceeded() {
    KeycloakUserDto user1 = user("user1");
    KeycloakUserDto user2 = user("user2");
    when(keycloakService.fetchUsers(anyCollection(), anySet())).thenReturn(List.of(user1, user2));
    doThrow(new RuntimeException("sync failed")).when(userReconciliationService).syncUser(user1);

    int count = userSyncService.syncFromKeycloak();

    // The bad row is logged and swallowed; only the good row is counted, and the batch still
    // finishes.
    assertEquals(1, count);
    verify(userReconciliationService).syncUser(user1);
    verify(userReconciliationService).syncUser(user2);
    verify(userReconciliationService).markMissingUsers(anySet());
    verify(bankHolderReconciliationService).reconcileAll();
  }

  @Test
  void syncFromKeycloak_swallowsABankReconcileFailureAfterASuccessfulRosterSync() {
    KeycloakUserDto user1 = user("user1");
    when(keycloakService.fetchUsers(anyCollection(), anySet())).thenReturn(List.of(user1));
    doThrow(new RuntimeException("bank hiccup"))
        .when(bankHolderReconciliationService)
        .reconcileAll();

    // The bank-side failure must not abort the core user sync — the count still reflects the
    // roster.
    int count = userSyncService.syncFromKeycloak();

    assertEquals(1, count);
    verify(userReconciliationService).syncUser(user1);
    verify(userReconciliationService).markMissingUsers(anySet());
    verify(bankHolderReconciliationService).reconcileAll();
  }

  @Test
  void syncFromKeycloak_logsTheRoleSummaryAndFlaggedCount_atInfoForAnOrdinaryRun() {
    when(keycloakService.fetchUsers(anyCollection(), anySet())).thenReturn(List.of(user("user1")));
    when(userReconciliationService.markMissingUsers(anySet())).thenReturn(2);

    withAppender(
        appender -> {
          userSyncService.syncFromKeycloak();

          ILoggingEvent flagged = eventContaining(appender, "no longer present in Keycloak");
          // Two leavers is the ordinary trickle: reported, but not an anomaly.
          assertEquals(Level.INFO, flagged.getLevel());
          assertTrue(flagged.getFormattedMessage().contains("2 local users"));
        });
    // The per-run role-mapping aggregate is emitted by the reconciliation service itself.
    verify(userReconciliationService).logRoleSyncSummary();
  }

  @Test
  void syncFromKeycloak_massSoftDeleteInOneRun_escalatesToWarn() {
    when(keycloakService.fetchUsers(anyCollection(), anySet())).thenReturn(List.of(user("user1")));
    when(userReconciliationService.markMissingUsers(anySet())).thenReturn(42);

    withAppender(
        appender -> {
          userSyncService.syncFromKeycloak();

          ILoggingEvent flagged = eventContaining(appender, "no longer present in Keycloak");
          // 42 accounts vanishing in a single run is what an upstream mass-deletion or a
          // half-degraded roster looks like — the run still "succeeds", so this line is the
          // only signal.
          assertEquals(Level.WARN, flagged.getLevel());
          assertTrue(flagged.getFormattedMessage().contains("42 local users"));
        });
  }

  @Test
  void syncFromKeycloak_withNoDepartures_saysNothingAboutSoftDeletes() {
    when(keycloakService.fetchUsers(anyCollection(), anySet())).thenReturn(List.of(user("user1")));
    when(userReconciliationService.markMissingUsers(anySet())).thenReturn(0);

    withAppender(
        appender -> {
          userSyncService.syncFromKeycloak();

          // Nobody left: the run must not emit a "0 users flagged" line every night.
          assertTrue(
              appender.list.stream()
                  .noneMatch(
                      e -> e.getFormattedMessage().contains("no longer present in Keycloak")));
        });
  }

  /**
   * Runs {@code body} with a {@link ListAppender} attached to the {@link UserSyncService} logger
   * and detaches it afterwards, so a failing assertion cannot leak the appender into other tests.
   *
   * @param body the assertions to run against the captured log events
   */
  private static void withAppender(java.util.function.Consumer<ListAppender<ILoggingEvent>> body) {
    Logger logger = (Logger) LoggerFactory.getLogger(UserSyncService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      body.accept(appender);
    } finally {
      logger.detachAppender(appender);
    }
  }

  /**
   * The first captured log event whose formatted message contains {@code needle}.
   *
   * @param appender the appender holding the captured events
   * @param needle the substring identifying the wanted line
   * @return that event
   */
  private static ILoggingEvent eventContaining(
      ListAppender<ILoggingEvent> appender, String needle) {
    return appender.list.stream()
        .filter(e -> e.getFormattedMessage().contains(needle))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no log line containing: " + needle));
  }

  private static KeycloakUserDto user(String name) {
    return new KeycloakUserDto(
        UUID.randomUUID(), name, name + "@test.com", true, Collections.emptySet(), null);
  }
}
