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
 * R6.e — REST surface for membership-row management on a single Squadron. Mounts under {@code
 * /api/v1/squadrons/{id}/members} so the URL itself documents the parent Squadron, mirroring the
 * R5.b layout of {@link SpecialCommandMembershipController} for SK memberships.
 *
 * <p>SPEZIALKOMMANDO_PLAN.md D3 + §5.6 + R6.d wrote the read side of the
 * Logistician/Mission-Manager flag onto the {@code org_unit_membership} row (the JWT converter now
 * consults the per-membership flag, not the legacy {@code app_user.is_logistician} / {@code
 * app_user.is_mission_manager} columns). This controller completes the write side: the admin
 * user-management UI can now flip the flag on the membership row directly, with optimistic-lock
 * detection.
 *
 * <p>Authorisation: every write goes through {@code hasRole('ADMIN')}. Unlike the SK side there is
 * no Squadron-Lead concept — Squadron member management has always been an ADMIN / Squadron-Officer
 * concern, and the existing pre-R6.e legacy endpoints ({@code PATCH
 * /api/v1/users/{id}/memberships}) also gated on ADMIN. The V95 CHECK constraint {@code
 * chk_org_unit_membership_lead_only_on_special_command} forbids the {@code is_lead} column being
 * set on a Squadron membership in the first place, so no {@code /lead} endpoint exists here.
 *
 * <p>The per-flag query-param toggle endpoints were removed once the member-edit page moved to the
 * version-aware membership-delta PATCH ({@code PATCH /api/v1/users/{id}/memberships}); Staffel flag
 * changes now flow through {@link OrgUnitMembershipService#reconcileStaffelMemberships(
 * de.greluc.krt.profit.basetool.backend.model.User, java.util.List)} or the version-aware PATCH
 * below.
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
              + " /api/v1/users/{id}/mission-manager query-param endpoints, which stay live as"
              + " transitional aliases during the R6.e soak.")
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
