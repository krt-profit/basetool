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

package de.greluc.krt.profit.basetool.backend.dashboard.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit tests for {@link AnnouncementReadController}: the caller id comes from the JWT and the
 * announcement id from the path.
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementReadControllerTest {

  @Mock private UserService userService;
  @Mock private UserMapper userMapper;
  @Mock private Jwt jwt;
  @InjectMocks private AnnouncementReadController controller;

  private static final UUID CALLER_ID = UUID.randomUUID();

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
