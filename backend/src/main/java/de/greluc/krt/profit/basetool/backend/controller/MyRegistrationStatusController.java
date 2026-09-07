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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.RegistrationStatusDto;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the calling user's own approval status (epic #720, Track 1). The frontend reads this once
 * per session to route a {@code PENDING}/{@code REJECTED} user to the waiting-for-approval page
 * rather than into the tool. (It read "rather than the guest surface" until ADR-0159 removed that
 * surface; the routing is unchanged, its alternative is now the login.) Available to any
 * authenticated user — including a pending one, whose only authority is {@code
 * ROLE_PENDING_APPROVAL}.
 */
@RestController
@RequestMapping("/api/v1/users/me/registration-status")
@RequiredArgsConstructor
public class MyRegistrationStatusController {

  private final UserService userService;

  /**
   * Returns the calling user's current approval status.
   *
   * @param jwt the caller's validated token
   * @return the caller's approval status
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public RegistrationStatusDto myStatus(@AuthenticationPrincipal Jwt jwt) {
    User me = userService.findById(userService.getUserIdFromJwt(jwt));
    return new RegistrationStatusDto(me.getApprovalStatus());
  }
}
