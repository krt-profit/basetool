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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.ApproveRegistrationRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.LinkRegistrationRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MergeAccountRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PendingRegistrationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RejectRegistrationRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.ReopenRegistrationRequest;
import de.greluc.krt.profit.basetool.backend.service.UserAccountMergeService;
import de.greluc.krt.profit.basetool.backend.service.UserRegistrationService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin queue for Discord registration approvals: lists pending or rejected registrations and
 * approves, rejects, reopens, links or merges them (REQ-SEC-017).
 *
 * <p>Every decision is optimistic-locked (stale {@code version} gives 409) and audited in {@code
 * user_approval_event}. Approval grants no Basetool roles by itself.
 */
@RestController
@RequestMapping("/api/v1/admin/registrations")
@RequiredArgsConstructor
public class DiscordRegistrationAdminController {

  private final UserService userService;
  private final UserRegistrationService userRegistrationService;

  /** The account merge behind the queue's duplicate-callsign remedy (REQ-SEC-045). */
  private final UserAccountMergeService userAccountMergeService;

  /**
   * Lists registrations by approval status, oldest first (REQ-SEC-034). Only {@code PENDING} and
   * {@code REJECTED} are accepted.
   *
   * @param status the approval status to list; defaults to {@code PENDING} when absent
   * @return the registrations in that status, oldest registration first
   * @throws BadRequestException when {@code status=ACTIVE} is requested
   */
  @GetMapping
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Operation(summary = "List registrations awaiting a decision, or the rejected ones.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Registrations in that status, oldest first."),
    @ApiResponse(responseCode = "400", description = "Unsupported status requested."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator.")
  })
  public List<PendingRegistrationDto> list(
      @RequestParam(name = "status", required = false)
          @Nullable
          @Parameter(schema = @Schema(allowableValues = {"PENDING", "REJECTED"}))
          ApprovalStatus status) {
    List<User> users =
        switch (status == null ? ApprovalStatus.PENDING : status) {
          case PENDING -> userRegistrationService.findPendingRegistrations();
          case REJECTED -> userRegistrationService.findRejectedRegistrations();
          case ACTIVE ->
              throw new BadRequestException(
                  "Only PENDING and REJECTED registrations can be listed here");
        };
    Set<String> colliding = userRegistrationService.findCollidingCallsigns(users);
    return users.stream().map(user -> toDto(user, colliding)).toList();
  }

  /**
   * Approves a pending registration (moves it to {@code ACTIVE}).
   *
   * @param id the registration to approve
   * @param jwt the calling admin's token (for the audit's deciding-admin id)
   * @param body optional body carrying the optimistic-lock version
   * @return the now-active user (with its bumped version)
   */
  @NotNull
  @PostMapping("/{id}/approve")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public PendingRegistrationDto approve(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Nullable @RequestBody(required = false) ApproveRegistrationRequest body) {
    Long version = body == null ? null : body.version();
    return toDto(
        userRegistrationService.approveUser(id, version, userService.getUserIdFromJwt(jwt)));
  }

  /**
   * Rejects a pending registration (moves it to {@code REJECTED}; the user stays without access).
   *
   * @param id the registration to reject
   * @param jwt the calling admin's token (for the audit's deciding-admin id)
   * @param body optional body carrying the reason and the optimistic-lock version
   * @return the now-rejected user (with its bumped version)
   */
  @NotNull
  @PostMapping("/{id}/reject")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public PendingRegistrationDto reject(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Nullable @Valid @RequestBody(required = false) RejectRegistrationRequest body) {
    String reason = body == null ? null : body.reason();
    Long version = body == null ? null : body.version();
    return toDto(
        userRegistrationService.rejectUser(id, reason, version, userService.getUserIdFromJwt(jwt)));
  }

  /**
   * Reopens a rejected registration, moving it back to {@code PENDING} so it can be decided again
   * (REQ-SEC-034).
   *
   * @param id the rejected registration to reopen
   * @param jwt the calling admin's token (for the audit's acting-admin id)
   * @param body optional body carrying a note and the optimistic-lock version
   * @return the now-pending registration (with its bumped version)
   */
  @NotNull
  @PostMapping("/{id}/reopen")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Operation(summary = "Reopen a rejected registration back into the approval queue.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The now-pending registration."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "No such registration."),
    @ApiResponse(
        responseCode = "409",
        description = "The registration is not rejected, or the supplied version is stale.")
  })
  public PendingRegistrationDto reopen(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Nullable @Valid @RequestBody(required = false) ReopenRegistrationRequest body) {
    String reason = body == null ? null : body.reason();
    Long version = body == null ? null : body.version();
    return toDto(
        userRegistrationService.reopenRegistration(
            id, reason, version, userService.getUserIdFromJwt(jwt)));
  }

  /**
   * Links a pending Discord registration onto an existing account: moves the Discord identity onto
   * the chosen account and removes the throwaway Discord-registered account (REQ-SEC-026).
   *
   * @param id the pending registration to link away
   * @param jwt the calling admin's token (for the audit's deciding-admin id)
   * @param body the target account id + the optimistic-lock version
   * @return the surviving target account (with its bumped version)
   */
  @NotNull
  @PostMapping("/{id}/link")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public PendingRegistrationDto link(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @NotNull @Valid @RequestBody LinkRegistrationRequest body) {
    return toDto(
        userRegistrationService.linkRegistrationToExistingAccount(
            id, body.targetUserId(), body.version(), userService.getUserIdFromJwt(jwt)));
  }

  /**
   * Merges an older account into this registration (REQ-SEC-046, ADR-0142).
   *
   * <p>Everything the source account owns moves onto the registration; records of who did something
   * stay where they are. Does not approve the registration, and leaves the emptied source row in
   * place.
   *
   * @param id the surviving registration the member logs into
   * @param adminUserId the acting admin, recorded as the audit actor
   * @param body the source account to empty, and the registration's optimistic-lock version
   * @return the surviving account
   */
  @NotNull
  @PostMapping("/{id}/merge")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Operation(summary = "Move an older account's own data onto this registration.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Merged; the surviving account is returned."),
    @ApiResponse(responseCode = "403", description = "Caller is not an administrator."),
    @ApiResponse(responseCode = "404", description = "Either account is unknown."),
    @ApiResponse(
        responseCode = "409",
        description =
            "The two ids are the same, both accounts hold a bank ledger, or the version is stale.")
  })
  public PendingRegistrationDto merge(
      @PathVariable UUID id,
      @CurrentUserId UUID adminUserId,
      @NotNull @Valid @RequestBody MergeAccountRequest body) {
    return toDto(userAccountMergeService.merge(body.sourceUserId(), id, adminUserId));
  }

  /**
   * Maps one registration, resolving its callsign collision for that row alone; list reads use
   * {@link #toDto(User, Set)} instead.
   *
   * @param user the registration to map
   * @return the DTO, with {@code callsignCollision} resolved for this row
   */
  @NotNull
  private PendingRegistrationDto toDto(User user) {
    return toDto(user, userRegistrationService.findCollidingCallsigns(List.of(user)));
  }

  /**
   * Maps one registration against a pre-resolved set of colliding callsigns.
   *
   * @param user the registration to map
   * @param collidingCallsigns lower-cased usernames held by more than one account
   * @return the DTO
   */
  @NotNull
  private PendingRegistrationDto toDto(@NotNull User user, Set<String> collidingCallsigns) {
    return new PendingRegistrationDto(
        user.getId(),
        user.getEffectiveName(),
        user.getDiscordGuildNickname(),
        user.getCreatedAt(),
        user.getApprovedAt(),
        user.getUsername() != null
            && collidingCallsigns.contains(user.getUsername().toLowerCase(Locale.ROOT)),
        user.getVersion());
  }
}
