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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDtoRedaction;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserMapper;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Records that the calling member has read an announcement, which clears the unread badge. The JWT
 * identifies the row; the user record comes back with the caller's own email.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Transactional
public class AnnouncementReadController {

  private final UserService userService;
  private final UserMapper userMapper;

  /**
   * Records that the calling user has read the given announcement (clears the unread badge).
   *
   * @param id announcement just read
   * @return the persisted DTO
   */
  @NotNull
  @PutMapping("/announcements/{id}/read")
  @PreAuthorize("isAuthenticated()")
  public UserDto updateReadAnnouncement(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @NotNull UUID id) {
    User me = userService.updateReadAnnouncement(userService.getUserIdFromJwt(jwt), id);
    return UserDtoRedaction.withSelfEmail(userMapper.toDto(me), me);
  }
}
