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

package de.greluc.krt.profit.basetool.backend.task;

import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.model.dto.KeycloakUserDto;
import de.greluc.krt.profit.basetool.backend.service.KeycloakService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserSyncTaskTest {

  @Mock private KeycloakService keycloakService;

  @Mock private UserService userService;

  // A real TaskMetrics (spied) so the wrapper genuinely runs the sync body; a mock would no-op it
  // and defeat every interaction assertion below.
  @Spy private TaskMetrics taskMetrics = new TaskMetrics(new SimpleMeterRegistry());

  @InjectMocks private UserSyncTask userSyncTask;

  @Test
  void syncUsers_shouldFetchAndSyncUsers() {
    KeycloakUserDto user1 =
        new KeycloakUserDto(
            UUID.randomUUID(),
            "user1",
            "u1@test.com",
            true,
            java.util.Collections.emptySet(),
            null);
    KeycloakUserDto user2 =
        new KeycloakUserDto(
            UUID.randomUUID(),
            "user2",
            "u2@test.com",
            true,
            java.util.Collections.emptySet(),
            null);

    when(keycloakService.fetchUsers()).thenReturn(List.of(user1, user2));

    userSyncTask.syncUsers();

    verify(keycloakService).fetchUsers();
    verify(userService).syncUser(user1);
    verify(userService).syncUser(user2);
  }

  @Test
  void syncUsers_shouldHandleEmptyList() {
    when(keycloakService.fetchUsers()).thenReturn(Collections.emptyList());

    userSyncTask.syncUsers();

    verify(keycloakService).fetchUsers();
    verifyNoInteractions(userService);
  }

  @Test
  void syncUsers_shouldContinueOnException() {
    KeycloakUserDto user1 =
        new KeycloakUserDto(
            UUID.randomUUID(),
            "user1",
            "u1@test.com",
            true,
            java.util.Collections.emptySet(),
            null);
    KeycloakUserDto user2 =
        new KeycloakUserDto(
            UUID.randomUUID(),
            "user2",
            "u2@test.com",
            true,
            java.util.Collections.emptySet(),
            null);

    when(keycloakService.fetchUsers()).thenReturn(List.of(user1, user2));
    doThrow(new RuntimeException("Sync failed")).when(userService).syncUser(user1);

    userSyncTask.syncUsers();

    verify(userService).syncUser(user1);
    verify(userService).syncUser(user2);
  }
}
