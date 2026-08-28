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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin queue for Discord registration approvals (epic #720, Track 1, REQ-SEC-017). Lists the
 * pending registrations and approves/rejects them. Admin-only; every decision is optimistic-locked
 * (a stale {@code version} surfaces as HTTP 409) and audited in {@code user_approval_event}.
 *
 * <p>Approval grants no Basetool roles by itself — after approval the admin seats the user's
 * roles/units via the existing tooling (Track 1 keeps role assignment manual).
 *
 * <p>The queue read also serves the rejected rows ({@code ?status=REJECTED}) and {@link #reopen}
 * reverses an erroneous rejection back into the queue (REQ-SEC-034) — the two halves of making a
 * mistaken rejection recoverable without a manual database write.
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
   * Lists registrations by approval status, oldest first — the pending queue by default, or the
   * rejected rows so an erroneous rejection can be found and reopened (REQ-SEC-034).
   *
   * <p>Only {@code PENDING} and {@code REJECTED} are accepted. {@code ACTIVE} is refused rather
   * than served: it would turn this small admin queue into an unbounded dump of every member, which
   * is the user-administration surface's job and carries a different DTO.
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
          // ACTIVE is a legal ApprovalStatus but not a legal argument here (it would dump every
          // member), so the published schema advertises only the two values that can succeed
          // rather than the whole enum springdoc would otherwise reflect.
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
    return users.stream().map(this::toDto).toList();
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
   * Reopens a rejected registration (moves it back to {@code PENDING} so it re-enters the queue and
   * can be decided again). This is the supported reversal of an erroneous rejection (REQ-SEC-034);
   * previously the only ways back were a manual production {@code UPDATE}, which bypasses the audit
   * trail, or deleting the account outright, which destroys its data.
   *
   * @param id the rejected registration to reopen
   * @param jwt the calling admin's token (for the audit's acting-admin id)
   * @param body optional body carrying a note and the optimistic-lock version
   * @return the now-pending registration (with its bumped version)
   */
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
   * Links a pending Discord registration onto an existing account (REQ-SEC-026): moves the Discord
   * identity onto the chosen account and removes the throwaway Discord-registered account. Used
   * when a member who already had an account registered anew via Discord (e.g. their Discord handle
   * differs from their in-app name, so the automatic collision check did not recognise them).
   *
   * @param id the pending registration to link away
   * @param jwt the calling admin's token (for the audit's deciding-admin id)
   * @param body the target account id + the optimistic-lock version
   * @return the surviving target account (with its bumped version)
   */
  @PostMapping("/{id}/link")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public PendingRegistrationDto link(
      @PathVariable UUID id,
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody LinkRegistrationRequest body) {
    return toDto(
        userRegistrationService.linkRegistrationToExistingAccount(
            id, body.targetUserId(), body.version(), userService.getUserIdFromJwt(jwt)));
  }

  /**
   * Merges an older account into this registration (REQ-SEC-045, ADR-0142 point 5).
   *
   * <p>The remedy for the "same callsign, different account" marker on this queue. Everything the
   * source account <em>owns</em> — stock, hangar, personal inventory and blueprints, memberships,
   * sign-ups, bank grants, notifications, evaluations — moves onto the registration named in the
   * path; everything that records who <em>did</em> something stays where it happened, because
   * re-pointing it would falsify history rather than repair an identity.
   *
   * <p>Deliberately separate from approving: the merge repairs the data, the approval admits the
   * member, and an admin should be able to do the first without being forced into the second. The
   * source row is left in place, emptied — removing it is the user-deletion flow's job and carries
   * its own fail-closed Keycloak probe.
   *
   * @param id the surviving registration — the account the member logs into now
   * @param adminUserId the acting admin, recorded as the audit actor
   * @param body the source account to empty, and the registration's optimistic-lock version
   * @return the surviving account
   */
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
      @Valid @RequestBody MergeAccountRequest body) {
    return toDto(userAccountMergeService.merge(body.sourceUserId(), id, adminUserId));
  }

  private PendingRegistrationDto toDto(User user) {
    return new PendingRegistrationDto(
        user.getId(),
        user.getEffectiveName(),
        user.getDiscordGuildNickname(),
        user.getCreatedAt(),
        user.getApprovedAt(),
        user.getVersion());
  }
}
