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
import de.greluc.krt.profit.basetool.backend.model.dto.ApproveRegistrationRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PendingRegistrationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RejectRegistrationRequest;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin queue for Discord registration approvals (epic #720, Track 1, REQ-SEC-017). Lists the
 * pending registrations and approves/rejects them. Admin-only; every decision is optimistic-locked
 * (a stale {@code version} surfaces as HTTP 409) and audited in {@code user_approval_event}.
 *
 * <p>Approval grants no Basetool roles by itself — after approval the admin seats the user's
 * roles/units via the existing tooling (Track 1 keeps role assignment manual).
 */
@RestController
@RequestMapping("/api/v1/admin/registrations")
@RequiredArgsConstructor
public class DiscordRegistrationAdminController {

  private final UserService userService;

  /**
   * Lists the registrations awaiting an admin decision, oldest first.
   *
   * @return the pending registrations
   */
  @GetMapping
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public List<PendingRegistrationDto> listPending() {
    return userService.findPendingRegistrations().stream().map(this::toDto).toList();
  }

  /**
   * Approves a pending registration (moves it to {@code ACTIVE}).
   *
   * @param id the registration to approve
   * @param jwt the calling admin's token (for the audit's deciding-admin id)
   * @param body optional body carrying the optimistic-lock version
   * @return the now-active user (with its bumped version)
   */
  @PostMapping("/{id}/approve")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public PendingRegistrationDto approve(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Nullable @RequestBody(required = false) ApproveRegistrationRequest body) {
    Long version = body == null ? null : body.version();
    return toDto(userService.approveUser(id, version, userService.getUserIdFromJwt(jwt)));
  }

  /**
   * Rejects a pending registration (moves it to {@code REJECTED}; the user stays without access).
   *
   * @param id the registration to reject
   * @param jwt the calling admin's token (for the audit's deciding-admin id)
   * @param body optional body carrying the reason and the optimistic-lock version
   * @return the now-rejected user (with its bumped version)
   */
  @PostMapping("/{id}/reject")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public PendingRegistrationDto reject(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Nullable @Valid @RequestBody(required = false) RejectRegistrationRequest body) {
    String reason = body == null ? null : body.reason();
    Long version = body == null ? null : body.version();
    return toDto(userService.rejectUser(id, reason, version, userService.getUserIdFromJwt(jwt)));
  }

  private PendingRegistrationDto toDto(User user) {
    return new PendingRegistrationDto(
        user.getId(),
        user.getEffectiveName(),
        user.getDiscordGuildNickname(),
        user.getCreatedAt(),
        user.getVersion());
  }
}
