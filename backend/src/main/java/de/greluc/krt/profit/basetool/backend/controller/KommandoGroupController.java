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

import de.greluc.krt.profit.basetool.backend.model.dto.CreateKommandoGroupRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.KommandoGroupDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateKommandoGroupRequest;
import de.greluc.krt.profit.basetool.backend.service.KommandoGroupService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for Kommandogruppen, the named sub-structures of a Staffel (REQ-ROLE-003).
 *
 * <p>Reads are open to any authenticated user. Writes are allowed to admins and to the
 * Staffelleiter of the group's squadron; update and delete resolve that squadron from the persisted
 * group, not from the path.
 */
@RestController
@RequiredArgsConstructor
public class KommandoGroupController {

  private final KommandoGroupService kommandoGroupService;

  /**
   * Lists the Kommandogruppen of a Staffel in display order.
   *
   * @param squadronId the Staffel whose groups to list; never {@code null}.
   * @return the squadron's groups, ascending by sort index.
   */
  @GetMapping("/api/v1/squadrons/{squadronId}/kommando-groups")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "List a squadron's Kommandogruppen")
  public List<KommandoGroupDto> list(@PathVariable @NotNull UUID squadronId) {
    return kommandoGroupService.listGroups(squadronId);
  }

  /**
   * Creates a Kommandogruppe in a Staffel (admin, or the squadron's Staffelleiter).
   *
   * @param squadronId the Staffel to create the group in; never {@code null}.
   * @param request the create payload; never {@code null}.
   * @return the persisted group.
   */
  @PostMapping("/api/v1/squadrons/{squadronId}/kommando-groups")
  @PreAuthorize(
      "hasRole('"
          + Roles.ADMIN
          + "') or @orgRoleManagementSecurityService.canManageKommandoGroups(#squadronId,"
          + " authentication)")
  @Operation(summary = "Create a Kommandogruppe (admin or the squadron's Staffelleiter)")
  public KommandoGroupDto create(
      @PathVariable @NotNull UUID squadronId,
      @RequestBody @Valid CreateKommandoGroupRequest request) {
    return kommandoGroupService.createGroup(squadronId, request);
  }

  /**
   * Renames and/or reorders a Kommandogruppe (admin, or the Staffelleiter of the group's squadron).
   *
   * @param groupId the group to update; never {@code null}.
   * @param request the update payload (name + sort index + version); never {@code null}.
   * @return the persisted group with the bumped version.
   */
  @PutMapping("/api/v1/kommando-groups/{groupId}")
  @PreAuthorize(
      "hasRole('"
          + Roles.ADMIN
          + "') or @orgRoleManagementSecurityService.canManageKommandoGroup(#groupId,"
          + " authentication)")
  @Operation(summary = "Rename / reorder a Kommandogruppe (admin or the squadron's Staffelleiter)")
  public KommandoGroupDto update(
      @PathVariable @NotNull UUID groupId, @RequestBody @Valid UpdateKommandoGroupRequest request) {
    return kommandoGroupService.updateGroup(groupId, request);
  }

  /**
   * Deletes a Kommandogruppe (admin, or the Staffelleiter of the group's squadron). Rejected while
   * the group still has assigned members.
   *
   * @param groupId the group to delete; never {@code null}.
   */
  @DeleteMapping("/api/v1/kommando-groups/{groupId}")
  @PreAuthorize(
      "hasRole('"
          + Roles.ADMIN
          + "') or @orgRoleManagementSecurityService.canManageKommandoGroup(#groupId,"
          + " authentication)")
  @Operation(summary = "Delete a Kommandogruppe (admin or the squadron's Staffelleiter)")
  public void delete(@PathVariable @NotNull UUID groupId) {
    kommandoGroupService.deleteGroup(groupId);
  }
}
