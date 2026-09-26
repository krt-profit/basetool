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

import de.greluc.krt.profit.basetool.backend.model.dto.AssignSquadronRankRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the in-squadron leadership ranks (Staffelleiter, Kommandoleiter,
 * stellvertretender Kommandoleiter, Ensign) of existing Staffel members (REQ-ROLE-003/004).
 *
 * <p>A Staffelleiter is appointed by the parent Bereichsleiter, the lower ranks by the squadron's
 * Staffelleiter, never by the appointee; admins may always act. Response DTOs are projected inside
 * the service transaction.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/squadrons/{squadronId}/ranks")
public class SquadronRoleController {

  private final OrgUnitMembershipService membershipService;

  /**
   * Assigns (or changes) a member's squadron leadership rank, optionally bound to a Kommandogruppe.
   *
   * @param squadronId the Staffel; never {@code null}.
   * @param userId the member to assign the rank to; never {@code null}.
   * @param request the rank + optional Kommandogruppe + optimistic-lock version; never {@code
   *     null}.
   * @return the persisted membership with the bumped version.
   */
  @PutMapping("/{userId}")
  @PreAuthorize(
      "hasRole('"
          + Roles.ADMIN
          + "') or @orgRoleManagementSecurityService.canAssignSquadronRank(#squadronId,"
          + " #request.role(), authentication)")
  @Operation(summary = "Assign a squadron leadership rank to a Staffel member")
  public OrgUnitMembershipDto assignRank(
      @PathVariable @NotNull UUID squadronId,
      @PathVariable @NotNull UUID userId,
      @NotNull @RequestBody @Valid AssignSquadronRankRequest request) {
    return membershipService.assignSquadronRankDto(
        squadronId, userId, request.role(), request.kommandoGroupId(), request.version());
  }

  /**
   * Clears a member's squadron leadership rank back to a plain member (unbinding any
   * Kommandogruppe).
   *
   * @param squadronId the Staffel; never {@code null}.
   * @param userId the member whose rank to clear; never {@code null}.
   * @param version the optimistic-lock version the client last read, or absent to skip the check.
   * @return the persisted membership with the bumped version.
   */
  @DeleteMapping("/{userId}")
  @PreAuthorize(
      "hasRole('"
          + Roles.ADMIN
          + "') or @orgRoleManagementSecurityService.canRemoveSquadronRank(#squadronId, #userId,"
          + " authentication)")
  @Operation(summary = "Clear a Staffel member's squadron leadership rank")
  public OrgUnitMembershipDto removeRank(
      @PathVariable @NotNull UUID squadronId,
      @PathVariable @NotNull UUID userId,
      @RequestParam(required = false) Long version) {
    return membershipService.removeSquadronRankDto(squadronId, userId, version);
  }
}
