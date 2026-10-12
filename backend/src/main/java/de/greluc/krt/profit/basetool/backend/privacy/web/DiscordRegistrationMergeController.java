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

package de.greluc.krt.profit.basetool.backend.privacy.web;

import de.greluc.krt.profit.basetool.backend.identity.api.PendingRegistrationDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRegistrationService;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.CurrentUserId;
import de.greluc.krt.profit.basetool.backend.privacy.api.MergeAccountRequest;
import de.greluc.krt.profit.basetool.backend.privacy.api.UserAccountMergeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The account merge behind the registration queue's duplicate-callsign remedy (REQ-SEC-045); the
 * rest of the queue is {@code DiscordRegistrationAdminController}.
 */
@RestController
@RequestMapping("/api/v1/users/admin/registrations")
@RequiredArgsConstructor
public class DiscordRegistrationMergeController {

  private final UserRegistrationService userRegistrationService;
  private final UserAccountMergeService userAccountMergeService;

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
    var merged = userAccountMergeService.merge(body.sourceUserId(), id, adminUserId);
    return PendingRegistrationDto.from(
        merged, userRegistrationService.findCollidingCallsigns(List.of(merged)));
  }
}
