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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link UserSyncService} — the Keycloak-&gt;local reconciliation shared by the
 * scheduled {@link de.greluc.krt.profit.basetool.backend.task.UserSyncTask} and the admin-triggered
 * manual sync endpoint.
 */
@ExtendWith(MockitoExtension.class)
class UserSyncServiceTest {

  @Mock private KeycloakService keycloakService;

  @Mock private UserService userService;

  @Mock private BankHolderReconciliationService bankHolderReconciliationService;

  @InjectMocks private UserSyncService userSyncService;

  @Test
  void syncFromKeycloak_fetchesUpsertsReconcilesAndReturnsTheSyncedCount() {
    KeycloakUserDto user1 = user("user1");
    KeycloakUserDto user2 = user("user2");
    when(keycloakService.fetchUsers(anyCollection(), anySet())).thenReturn(List.of(user1, user2));

    int count = userSyncService.syncFromKeycloak();

    assertEquals(2, count);
    verify(userService).syncUser(user1);
    verify(userService).syncUser(user2);
    verify(userService).markMissingUsers(anySet());
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
    verify(userService, never()).syncUser(any(KeycloakUserDto.class));
    verify(userService, never()).markMissingUsers(anySet());
    verifyNoInteractions(bankHolderReconciliationService);
  }

  @Test
  void syncFromKeycloak_continuesAndCountsOnlyTheUsersThatSucceeded() {
    KeycloakUserDto user1 = user("user1");
    KeycloakUserDto user2 = user("user2");
    when(keycloakService.fetchUsers(anyCollection(), anySet())).thenReturn(List.of(user1, user2));
    doThrow(new RuntimeException("sync failed")).when(userService).syncUser(user1);

    int count = userSyncService.syncFromKeycloak();

    // The bad row is logged and swallowed; only the good row is counted, and the batch still
    // finishes.
    assertEquals(1, count);
    verify(userService).syncUser(user1);
    verify(userService).syncUser(user2);
    verify(userService).markMissingUsers(anySet());
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
    verify(userService).syncUser(user1);
    verify(userService).markMissingUsers(anySet());
    verify(bankHolderReconciliationService).reconcileAll();
  }

  private static KeycloakUserDto user(String name) {
    return new KeycloakUserDto(
        UUID.randomUUID(), name, name + "@test.com", true, Collections.emptySet(), null);
  }
}
