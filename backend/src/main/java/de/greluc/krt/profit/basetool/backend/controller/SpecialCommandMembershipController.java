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
import de.greluc.krt.profit.basetool.backend.model.dto.MembershipLeadToggleRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for membership management on a single Spezialkommando. Endpoints live under {@code
 * /api/v1/special-commands/{id}/members} so the URL itself documents the parent SK; the controller
 * code stays focused on member-list mutations and never has to reach into SK-lifecycle concerns.
 *
 * <p>Authorisation: every member-list mutation goes through
 * {@code @specialCommandSecurityService.canManageMembers(#id, authentication)} which combines
 * "ADMIN" and "Lead of this SK" into a single boolean. The dedicated Lead-toggle endpoint is
 * additionally hard-gated to {@code hasRole('ADMIN')} so a Lead cannot promote themselves or
 * someone else to Lead (would otherwise be a privilege-escalation path).
 */
@RestController
@RequestMapping("/api/v1/special-commands/{id}/members")
@RequiredArgsConstructor
public class SpecialCommandMembershipController {

  private final OrgUnitMembershipService membershipService;

  /**
   * Lists every member of the given Spezialkommando. Used by the admin roster page; the response
   * includes the per-membership role flags so the UI can render the badge column without a
   * follow-up call.
   *
   * @param id Spezialkommando id.
   * @return the membership list; possibly empty if no member has been added yet.
   */
  @GetMapping
  @PreAuthorize("@specialCommandSecurityService.canManageMembers(#id, authentication)")
  @Operation(
      summary = "List Spezialkommando members",
      description = "Returns every membership row of the given Spezialkommando.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Membership list."),
    @ApiResponse(
        responseCode = "403",
        description =
            "Caller is neither ADMIN nor Lead of this Spezialkommando — see"
                + " SpecialCommandSecurityService.canManageMembers."),
    @ApiResponse(responseCode = "404", description = "No Spezialkommando matches the given id.")
  })
  public List<OrgUnitMembershipDto> listMembers(@PathVariable @NotNull UUID id) {
    return membershipService.listMemberDtos(id);
  }

  /**
   * Adds the given user to the given Spezialkommando. ADMIN or Lead-of-this-SK only.
   *
   * @param id Spezialkommando id.
   * @param userId user to add.
   * @return the persisted membership DTO with role flags defaulted to {@code false}.
   */
  @PostMapping("/{userId}")
  @PreAuthorize("@specialCommandSecurityService.canManageMembers(#id, authentication)")
  @Operation(
      summary = "Add a member to a Spezialkommando",
      description =
          "Adds the given user to the Spezialkommando with all role flags initially false. ADMIN"
              + " or Lead-of-this-SK only.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Member added."),
    @ApiResponse(
        responseCode = "403",
        description = "Caller is neither ADMIN nor Lead of this Spezialkommando."),
    @ApiResponse(
        responseCode = "404",
        description = "No Spezialkommando matches the given id, or the user does not exist."),
    @ApiResponse(responseCode = "409", description = "User is already a member of this SK.")
  })
  public OrgUnitMembershipDto addMember(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    return membershipService.addMemberDto(id, userId);
  }

  /**
   * Removes the given user from the given Spezialkommando. ADMIN or Lead-of-this-SK only.
   *
   * @param id Spezialkommando id.
   * @param userId user to remove.
   */
  @DeleteMapping("/{userId}")
  @PreAuthorize("@specialCommandSecurityService.canManageMembers(#id, authentication)")
  @Operation(
      summary = "Remove a member from a Spezialkommando",
      description = "Removes the given user's membership row. ADMIN or Lead-of-this-SK only.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Member removed."),
    @ApiResponse(
        responseCode = "403",
        description = "Caller is neither ADMIN nor Lead of this Spezialkommando."),
    @ApiResponse(
        responseCode = "404",
        description =
            "No Spezialkommando matches the given id, or the user is not a member of this SK.")
  })
  public void removeMember(@PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    membershipService.removeMember(id, userId);
  }

  /**
   * Flips the per-membership Logistician / Mission Manager flags. ADMIN or Lead-of-this-SK only.
   * Each flag is independent; clients send only the fields they want to change.
   *
   * @param id Spezialkommando id.
   * @param userId user whose membership to patch.
   * @param request patch payload; carries the current version for optimistic-lock detection.
   * @return the persisted membership DTO with the bumped version.
   */
  @PatchMapping("/{userId}")
  @PreAuthorize("@specialCommandSecurityService.canManageMembers(#id, authentication)")
  @Operation(
      summary = "Patch per-membership role flags",
      description =
          "Flips is_logistician and / or is_mission_manager on the membership row. ADMIN or"
              + " Lead-of-this-SK only. is_lead is NOT patchable through this endpoint — see the"
              + " dedicated /lead endpoint.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Flags patched."),
    @ApiResponse(responseCode = "400", description = "Validation error on the inbound payload."),
    @ApiResponse(
        responseCode = "403",
        description = "Caller is neither ADMIN nor Lead of this Spezialkommando."),
    @ApiResponse(
        responseCode = "404",
        description =
            "No Spezialkommando matches the given id, or the user is not a member of this SK."),
    @ApiResponse(
        responseCode = "409",
        description = "Optimistic-lock conflict — the membership row has been updated since.")
  })
  public OrgUnitMembershipDto patchFlags(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @RequestBody @Valid MembershipFlagsPatchRequest request) {
    return membershipService.patchFlagsDto(id, userId, request);
  }

  /**
   * Flips the {@code is_lead} flag on the membership row. Admin, or — from epic #800 (REQ-ROLE-004)
   * — the Bereichsleiter of the SK's parent Bereich (delegated appointment). A Lead can never
   * promote themselves or another member to Lead: the SK-Lead rung is appointed strictly from the
   * tier above (the parent Bereich), never from within the SK.
   *
   * @param id Spezialkommando id.
   * @param userId user whose membership to update.
   * @param request toggle payload; carries the current version.
   * @return the persisted membership DTO with the bumped version.
   */
  @PatchMapping("/{userId}/lead")
  @PreAuthorize(
      "hasRole('"
          + Roles.ADMIN
          + "') or @orgRoleManagementSecurityService.canAppointSkLead(#id, authentication)")
  @Operation(
      summary = "Toggle the SK-Lead flag on a membership",
      description =
          "Promotes or demotes a Spezialkommando member to / from Lead. Admin or the Bereichsleiter"
              + " of the SK's parent Bereich; a Lead can never promote themselves or another"
              + " member.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Lead flag toggled."),
    @ApiResponse(responseCode = "400", description = "Validation error on the inbound payload."),
    @ApiResponse(
        responseCode = "403",
        description = "Caller is neither admin nor the Bereichsleiter of the SK's parent Bereich."),
    @ApiResponse(
        responseCode = "404",
        description =
            "No Spezialkommando matches the given id, or the user is not a member of this SK."),
    @ApiResponse(
        responseCode = "409",
        description = "Optimistic-lock conflict — the membership row has been updated since.")
  })
  public OrgUnitMembershipDto toggleLead(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID userId,
      @RequestBody @Valid MembershipLeadToggleRequest request) {
    return membershipService.toggleLeadDto(id, userId, request);
  }
}
