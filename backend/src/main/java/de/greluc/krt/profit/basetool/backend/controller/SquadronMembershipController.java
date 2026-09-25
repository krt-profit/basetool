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

import de.greluc.krt.profit.basetool.backend.model.dto.MembershipFlagsPatchRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for membership rows of a single Squadron.
 *
 * <p>Every write is ADMIN-only. Squadrons have no Lead concept, so no {@code /lead} endpoint
 * exists; Staffel assignments also flow through the membership-delta PATCH on the user resource.
 */
@RestController
@RequestMapping("/api/v1/squadrons/{id}/members")
@RequiredArgsConstructor
public class SquadronMembershipController {

  private final OrgUnitMembershipService membershipService;

  /**
   * Flips the per-membership {@code is_logistician} / {@code is_mission_manager} flags on a single
   * Squadron member's membership row. ADMIN-only. Each flag is independent; clients send only the
   * fields they want to change. Carries the row's current {@code version} as the optimistic-lock
   * token so two concurrent admin edits cannot silently clobber one another.
   *
   * @param id Squadron id; never {@code null}.
   * @param userId user whose membership to patch; never {@code null}.
   * @param request patch payload; carries the current version for optimistic-lock detection.
   * @return the persisted membership DTO with the bumped version.
   */
  @PatchMapping("/{userId}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Operation(
      summary = "Patch per-Squadron-membership role flags",
      description =
          "Flips is_logistician and / or is_mission_manager on the Staffel membership row."
              + " ADMIN only. Replaces the legacy /api/v1/users/{id}/logistician and"
              + " /api/v1/users/{id}/mission-manager query-param endpoints, which have been"
              + " removed.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Flags patched."),
    @ApiResponse(responseCode = "400", description = "Validation error on the inbound payload."),
    @ApiResponse(responseCode = "403", description = "Caller is not ADMIN."),
    @ApiResponse(
        responseCode = "404",
        description = "No Squadron matches the given id, or the user is not a member."),
    @ApiResponse(
        responseCode = "409",
        description = "Optimistic-lock conflict — the membership row has been updated since.")
  })
  public OrgUnitMembershipDto patchFlags(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @RequestBody @Valid MembershipFlagsPatchRequest request) {
    return membershipService.patchSquadronMemberFlagsDto(id, userId, request);
  }
}
