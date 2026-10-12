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

import de.greluc.krt.profit.basetool.backend.blueprint.api.MemberBlueprintSharings;
import de.greluc.krt.profit.basetool.backend.identity.api.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.identity.api.UserSyncResultDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserSyncService;
import de.greluc.krt.profit.basetool.backend.mission.api.MemberPayoutSettings;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserDirectoryService;
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
  @Mock private UserDirectoryService userDirectoryService;

  @Mock private UserSyncService userSyncService;
  @Mock private MemberPayoutSettings memberPayoutSettings;
  @Mock private MemberBlueprintSharings memberBlueprintSharings;
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
  void getMyPayoutPreference_resolvesIdFromJwt_andReturnsPreferenceAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(memberPayoutSettings.snapshotOf(CALLER_ID))
        .thenReturn(new MemberPayoutSettings.Snapshot(PayoutPreference.DONATE, 4L));

    UserController.MyPayoutPreferenceResponse result = controller.getMyPayoutPreference(jwt);

    assertEquals(PayoutPreference.DONATE, result.defaultPayoutPreference());
    assertEquals(4L, result.version());
  }

  @Test
  void getMyPayoutPreference_returnsNullPreference_whenUserNeverChose() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(memberPayoutSettings.snapshotOf(CALLER_ID))
        .thenReturn(new MemberPayoutSettings.Snapshot(null, 0L));

    UserController.MyPayoutPreferenceResponse result = controller.getMyPayoutPreference(jwt);

    assertNull(
        result.defaultPayoutPreference(), "no explicit choice surfaces as a null preference");
    assertEquals(0L, result.version());
  }

  @Test
  void updateMyPayoutPreference_resolvesIdFromJwt_andForwardsPreferenceAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    UserController.MyPayoutPreferenceRequest req =
        new UserController.MyPayoutPreferenceRequest(PayoutPreference.DONATE, 2L);
    when(memberPayoutSettings.update(CALLER_ID, PayoutPreference.DONATE, 2L))
        .thenReturn(new MemberPayoutSettings.Snapshot(PayoutPreference.DONATE, 3L));

    UserController.MyPayoutPreferenceResponse result =
        controller.updateMyPayoutPreference(jwt, req);

    assertEquals(PayoutPreference.DONATE, result.defaultPayoutPreference());
    assertEquals(3L, result.version());
    verify(memberPayoutSettings).update(CALLER_ID, PayoutPreference.DONATE, 2L);
  }

  @Test
  void getMyBlueprintSharing_resolvesIdFromJwt_andReturnsFlagAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(memberBlueprintSharings.snapshotOf(CALLER_ID))
        .thenReturn(new MemberBlueprintSharings.Snapshot(true, 4L));

    UserController.MyBlueprintSharingResponse result = controller.getMyBlueprintSharing(jwt);

    assertTrue(result.shareBlueprintsGlobally());
    assertEquals(4L, result.version());
  }

  @Test
  void updateMyBlueprintSharing_resolvesIdFromJwt_andForwardsFlagAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    UserController.MyBlueprintSharingRequest req =
        new UserController.MyBlueprintSharingRequest(true, 2L);
    when(memberBlueprintSharings.update(CALLER_ID, true, 2L))
        .thenReturn(new MemberBlueprintSharings.Snapshot(true, 3L));

    UserController.MyBlueprintSharingResponse result =
        controller.updateMyBlueprintSharing(jwt, req);

    assertTrue(result.shareBlueprintsGlobally());
    assertEquals(3L, result.version());
    verify(memberBlueprintSharings).update(CALLER_ID, true, 2L);
  }

  @Test
  void updateReadAnnouncement_resolvesIdFromJwt_andForwardsAnnouncementId() {
    UUID announcementId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    User updated = new User();
    updated.setEmail("me@example.invalid");
    when(userService.updateReadAnnouncement(CALLER_ID, announcementId)).thenReturn(updated);
    UserDto dto = mockDto(CALLER_ID);
    when(userMapper.toDto(updated)).thenReturn(dto);

    UserDto result = controller.updateReadAnnouncement(jwt, announcementId);

    assertNotNull(result);
    assertEquals(CALLER_ID, result.id());
    assertEquals("me@example.invalid", result.email());
    verify(userService).updateReadAnnouncement(CALLER_ID, announcementId);
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
}
