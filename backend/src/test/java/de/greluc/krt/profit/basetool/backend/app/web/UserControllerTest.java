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

package de.greluc.krt.profit.basetool.backend.app.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceSearchService;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.identity.api.UserSyncResultDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserSyncService;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserMapper;
import de.greluc.krt.profit.basetool.backend.platform.api.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.platform.api.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserDeletionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit tests for {@link UserController}.
 *
 * <ul>
 *   <li>{@code /me} endpoints take the caller id from the JWT, never from the URL.
 *   <li>Admin endpoints forward path id and DTO fields verbatim.
 *   <li>The {@code lookup} endpoint returns the service's reference DTOs unmapped.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

  @Mock private UserService userService;
  @Mock private UserDeletionService userDeletionService;
  @Mock private UserMapper userMapper;
  @Mock private UserReferenceSearchService userReferenceSearchService;

  @Mock private UserSyncService userSyncService;
  @Mock private TaskMetrics taskMetrics;
  @Mock private Jwt jwt;

  @InjectMocks private UserController controller;

  private static final UUID CALLER_ID = UUID.randomUUID();

  @Test
  void syncUsersNow_runsTheReconciliationThroughTaskMetricsAndReturnsTheCount() {
    when(taskMetrics.recordCountingRethrow(eq(ScheduledJob.USER_SYNC), any())).thenReturn(42);

    UserSyncResultDto result = controller.syncUsersNow();

    assertEquals(42, result.syncedCount());
    verify(taskMetrics).recordCountingRethrow(eq(ScheduledJob.USER_SYNC), any());
  }

  @Test
  void syncUsersNow_propagatesASyncFailureRatherThanSwallowingIt() {
    when(taskMetrics.recordCountingRethrow(eq(ScheduledJob.USER_SYNC), any()))
        .thenThrow(new IllegalStateException("keycloak down"));

    assertThrows(IllegalStateException.class, () -> controller.syncUsersNow());
  }

  @Test
  void lookupUsers_returnsReferenceListDirectlyFromService() {
    List<UserReferenceDto> refs =
        List.of(
            new UserReferenceDto(UUID.randomUUID(), "alice", "Alice", "Alice", 5),
            new UserReferenceDto(UUID.randomUUID(), "bob", "Bob", "Bob", 4));
    when(userService.findAllReference()).thenReturn(refs);

    List<UserReferenceDto> result = controller.lookupUsers();

    assertSame(refs, result);
    verifyNoInteractions(userMapper);
  }

  @Test
  void deleteUser_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.deleteUser(id);

    verify(userDeletionService).deleteUser(id);
    verifyNoMoreInteractions(userService, userDeletionService, userMapper);
  }

  private static UserDto mockDto(UUID id) {
    return new UserDto(
        id,
        "u",
        "U",
        "U",
        "u@example.com",
        5,
        "desc",
        java.util.Set.of(),
        java.util.Set.of(),
        null,
        false,
        false,
        true,
        null,
        java.util.List.of(),
        1L,
        null,
        false);
  }

  @Test
  void searchUserReferences_delegatesToTheReferenceSearch() {
    PageResponse<UserReferenceDto> page = PageResponse.of(new PageImpl<>(List.of()));
    when(userReferenceSearchService.search("ali", 2, 10, "rank")).thenReturn(page);

    assertSame(page, controller.searchUserReferences("ali", 2, 10, "rank"));
  }
}
