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

package de.greluc.krt.profit.basetool.backend.orgunit.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserDirectoryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserMapper;
import java.time.LocalDate;
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
 * Unit tests for {@link UserProfileController}: {@code /me} endpoints take the caller id from the
 * JWT, never from the URL; the list, search and read endpoints delegate to the directory; the admin
 * attribute edit forwards every field.
 */
@ExtendWith(MockitoExtension.class)
class UserProfileControllerTest {

  @Mock private UserService userService;
  @Mock private UserMapper userMapper;
  @Mock private UserDirectoryService userDirectoryService;
  @Mock private Jwt jwt;
  @InjectMocks private UserProfileController controller;

  private static final UUID CALLER_ID = UUID.randomUUID();

  @Test
  void getCurrentUser_resolvesIdFromJwt_neverFromURL() {
    User entity = new User();
    entity.setEmail("me@example.invalid");
    UserDto dto = mockDto(CALLER_ID);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(userService.findById(CALLER_ID)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(dto);

    UserDto result = controller.getCurrentUser(jwt);

    assertNotNull(result);
    assertEquals(CALLER_ID, result.id());
    assertEquals("me@example.invalid", result.email());
    verify(userService).getUserIdFromJwt(jwt);
    verify(userService).findById(CALLER_ID);
  }

  @Test
  void updateMyDescription_resolvesIdFromJwt_andForwardsAllFields() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);

    UserProfileController.UserDescriptionRequest req =
        new UserProfileController.UserDescriptionRequest("Pilot extraordinaire", "Ace", 2L);

    User updated = new User();
    updated.setEmail("me@example.invalid");
    UserDto dto = mockDto(CALLER_ID);
    when(userService.updateUserDescription(CALLER_ID, "Pilot extraordinaire", "Ace", 2L))
        .thenReturn(updated);
    when(userMapper.toDto(updated)).thenReturn(dto);

    UserDto result = controller.updateMyDescription(jwt, req);

    assertNotNull(result);
    assertEquals(CALLER_ID, result.id());
    assertEquals("me@example.invalid", result.email());
    verify(userService).updateUserDescription(CALLER_ID, "Pilot extraordinaire", "Ace", 2L);
  }

  @Test
  void updateUserAttributes_forwardsAllFieldsToService() {
    UUID id = UUID.randomUUID();

    UserProfileController.UserAttributesRequest req =
        new UserProfileController.UserAttributesRequest(
            7, "desc", "name", 3L, LocalDate.of(2024, 1, 15));

    User updated = new User();
    UserDto dto = mockDto(id);
    when(userService.updateUserAttributes(id, 7, "desc", "name", 3L, LocalDate.of(2024, 1, 15)))
        .thenReturn(updated);
    when(userMapper.toDto(updated)).thenReturn(dto);

    UserDto result = controller.updateUserAttributes(id, req);

    assertSame(dto, result);
    verify(userService).updateUserAttributes(id, 7, "desc", "name", 3L, LocalDate.of(2024, 1, 15));
  }

  @Test
  void updateUserAttributes_withNullJoinDate_forwardsNull() {
    UUID id = UUID.randomUUID();
    UserProfileController.UserAttributesRequest req =
        new UserProfileController.UserAttributesRequest(3, null, null, 1L, null);

    when(userService.updateUserAttributes(eq(id), eq(3), any(), any(), eq(1L), eq(null)))
        .thenReturn(new User());
    when(userMapper.toDto(any())).thenReturn(mockDto(id));

    controller.updateUserAttributes(id, req);

    verify(userService).updateUserAttributes(id, 3, null, null, 1L, null);
  }

  @Test
  void getAllUsers_delegatesToTheDirectory() {
    PageResponse<UserDto> page = PageResponse.of(new PageImpl<>(List.of(mockDto(CALLER_ID))));
    when(userDirectoryService.list(1, 20, "rank")).thenReturn(page);

    assertSame(page, controller.getAllUsers(1, 20, "rank"));
  }

  @Test
  void searchUsers_delegatesToTheDirectory() {
    PageResponse<UserDto> page = PageResponse.of(new PageImpl<>(List.of(mockDto(CALLER_ID))));
    when(userDirectoryService.search("ali", 0, 10, null)).thenReturn(page);

    assertSame(page, controller.searchUsers("ali", 0, 10, null));
  }

  @Test
  void getUserById_delegatesToTheDirectory() {
    UserDto dto = mockDto(CALLER_ID);
    when(userDirectoryService.get(CALLER_ID)).thenReturn(dto);

    assertSame(dto, controller.getUserById(CALLER_ID));
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
