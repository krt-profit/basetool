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

package de.greluc.krt.profit.basetool.backend.orgunit.web;

import de.greluc.krt.profit.basetool.backend.identity.api.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipDeltaResponse;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for a member's org-unit memberships: the picker options of a user and of the caller,
 * the caller's org-unit ids, and the admin membership delta and detail.
 *
 * <p>The {@code /me} endpoints derive the caller from the JWT; the delta write and the detail read
 * are admin-scoped.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Transactional
public class MemberMembershipController {

  private final UserService userService;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  /**
   * Lists the org units a user belongs to as picker-optimised {@link OrgUnitMembershipOptionDto}
   * rows for the owner picker.
   *
   * <p>Open to every authenticated member and {@code BANK_EMPLOYEE} (REQ-BANK-044); the response
   * carries only org-unit names and shorthands, no personal data.
   *
   * @param id the user id whose memberships to list; never {@code null}.
   * @param allKinds {@code true} to include all four org-unit kinds (Staffel, SK, Bereich,
   *     Organisationsleitung); {@code false} for Staffel and SK only.
   * @return option DTOs sorted Staffel-first then SK alphabetical, or top-down by kind when {@code
   *     allKinds=true}; never {@code null}, possibly empty.
   */
  @GetMapping("/org-units/members/{id}/memberships")
  @PreAuthorize(
      "hasAnyRole('"
          + Roles.ADMIN
          + "', '"
          + Roles.OFFICER
          + "', '"
          + Roles.KRT_MEMBER
          + "', '"
          + Roles.BANK_EMPLOYEE
          + "')")
  @Transactional(readOnly = true)
  public List<OrgUnitMembershipOptionDto> getUserMemberships(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false, defaultValue = "false") boolean allKinds) {
    return allKinds
        ? orgUnitMembershipQueryService.listDirectMembershipOptions(id)
        : orgUnitMembershipQueryService.listOptionsForUser(id);
  }

  /**
   * Returns the calling user's pickable owning-org-unit options for create forms: direct
   * memberships plus the units reachable through leadership (REQ-ORG-016, REQ-ORG-018).
   *
   * <p>Resolved for the caller only; for an ordinary member it equals their direct memberships.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @return the caller's pickable org-unit options across all reachable kinds; never {@code null}.
   */
  @GetMapping("/org-units/me/pickable")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public List<OrgUnitMembershipOptionDto> getMyPickableOrgUnits(@AuthenticationPrincipal Jwt jwt) {
    return orgUnitMembershipQueryService.listPickerOptionsWithDescendants(
        userService.getUserIdFromJwt(jwt));
  }

  /**
   * Lists the org units the calling user is a direct member of, as {@link
   * OrgUnitMembershipOptionDto} rows; the JWT-resolved twin of {@code GET /{id}/memberships}.
   *
   * <p>Open to every authenticated caller; no memberships yield an empty list, not 403.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @param allKinds {@code true} to include all four org-unit kinds (Staffel, SK, Bereich,
   *     Organisationsleitung); {@code false} for Staffel and SK only.
   * @return option DTOs, sorted as the sibling endpoint sorts them; never {@code null}, possibly
   *     empty.
   */
  @GetMapping("/org-units/me/memberships")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public List<OrgUnitMembershipOptionDto> getMyMemberships(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false, defaultValue = "false") boolean allKinds) {
    UUID callerId = userService.getUserIdFromJwt(jwt);
    return allKinds
        ? orgUnitMembershipQueryService.listDirectMembershipOptions(callerId)
        : orgUnitMembershipQueryService.listOptionsForUser(callerId);
  }

  /**
   * Returns the ids of every org unit the calling user is a direct member of, across all kinds and
   * without leadership cascade (REQ-MISSION-012).
   *
   * <p>Open to every authenticated caller; no memberships yield an empty set.
   *
   * @param jwt caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @return the caller's direct org-unit ids; never {@code null}, possibly empty.
   */
  @GetMapping("/org-units/me/ids")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public Set<UUID> getMyOrgUnitIds(@AuthenticationPrincipal Jwt jwt) {
    return orgUnitMembershipQueryService.findDirectMembershipOrgUnitIds(
        userService.getUserIdFromJwt(jwt));
  }

  /**
   * Applies a membership delta (Staffel assignments, flag toggles, SK add/remove/patch) for a user
   * in one atomic transaction.
   *
   * <p>Each change carries its own {@code version}; one stale row rolls back the whole batch with
   * 409.
   *
   * @param id user primary key.
   * @param request the delta to apply; never {@code null}, but both halves may be {@code null} /
   *     empty.
   * @return the user's complete post-write membership list.
   */
  @NotNull
  @PatchMapping("/org-units/members/{id}/memberships")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public MembershipDeltaResponse patchMemberships(
      @PathVariable @NotNull UUID id, @RequestBody @Valid MembershipDeltaRequest request) {
    userService.applyMembershipDelta(id, request);
    return new MembershipDeltaResponse(
        orgUnitMembershipQueryService.findAllMembershipDtosForUser(id));
  }

  /**
   * Returns the user's complete membership set as full DTOs with per-membership flags and {@code
   * version}, backing the admin member-edit form (ADMIN only).
   *
   * @param id user primary key.
   * @return the user's memberships (Staffel + every SK) as full {@link
   *     de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipDto}s, wrapped in the
   *     same response shape the membership-delta PATCH returns.
   */
  @NotNull
  @GetMapping("/org-units/members/{id}/memberships/detail")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  public MembershipDeltaResponse getMembershipsDetail(@PathVariable @NotNull UUID id) {
    return new MembershipDeltaResponse(
        orgUnitMembershipQueryService.findAllMembershipDtosForUser(id));
  }
}
