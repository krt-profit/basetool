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

import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.NotificationService;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only surface for the caller's own context: the effective org unit, the capability flags, the
 * pinnable org units and the combined layout answer.
 *
 * <p>The active org unit is chosen in the frontend and relayed on every call via the {@code
 * X-Active-Org-Unit-Id} header.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MeController {

  private final OwnerScopeService ownerScopeService;

  /** Answers role questions through the configured hierarchy rather than by literal match. */
  private final AuthHelperService authHelperService;

  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  private final UserService userService;

  /** Supplies the unread-notification count of the layout read. */
  private final NotificationService notificationService;

  /**
   * Returns the org-unit context applied to staffel-scoped queries for this request.
   *
   * <p>Admins get the relayed header value; non-admins get the header only if it matches one of
   * their memberships, otherwise their home Staffel. {@code orgUnitId} is {@code null} for admin
   * "all OrgUnits" mode or a user without a home Staffel.
   *
   * @return current effective org-unit context; never {@code null}.
   */
  @NotNull
  @GetMapping("/active-org-unit")
  public ActiveOrgUnitResponse getActiveOrgUnit() {
    return new ActiveOrgUnitResponse(ownerScopeService.currentOrgUnitId().orElse(null));
  }

  /**
   * Returns the caller's UI capability flags, which decide the visible menu entries and page
   * redirects.
   *
   * <p>The {@code isLogisticianOrAbove} / {@code isMissionManagerOrAbove} / {@code isAdmin} flags
   * reflect authorisation through the role hierarchy, unlike {@code UserDto}'s Staffel-membership
   * flags, which are {@code false} for admins (REQ-SEC-030).
   *
   * @return the caller's UI capability flags; never {@code null}.
   */
  @NotNull
  @GetMapping("/capabilities")
  @Operation(
      summary = "Per-principal UI capability flags (blueprint overview, job orders, bank staff).")
  public CapabilitiesResponse getCapabilities() {
    return new CapabilitiesResponse(
        ownerScopeService.canAccessBlueprintOverview(),
        ownerScopeService.canViewJobOrders(),
        ownerScopeService.canViewOwnJobOrders(),
        authHelperService.hasReachableRole(Roles.authority(Roles.BANK_EMPLOYEE)),
        authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)),
        authHelperService.isLogisticianOrAbove(),
        authHelperService.hasReachableRole(Roles.authority(Roles.MISSION_MANAGER)),
        authHelperService.isAdmin());
  }

  /**
   * Returns the org units the caller may pin as their active context, sorted OL, Bereich, Staffel,
   * SK, then by name.
   *
   * <p>Admins get every active unit of every kind; others get their member units plus the units
   * reached through a Bereich or OL leadership seat (REQ-ORG-015).
   *
   * @param jwt the caller's JWT; never {@code null} thanks to the {@code @PreAuthorize}.
   * @return the pinnable options; never {@code null}, possibly empty for a membership-less
   *     non-admin.
   */
  @GetMapping("/org-units")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  @Operation(
      summary = "List the org units the caller may pin as their active context",
      description =
          "Admins get every active org unit of all four kinds; everyone else gets the units they"
              + " belong to or reach through a Bereich/OL seat. Encapsulates the branch both"
              + " clients would otherwise duplicate.")
  @ApiResponses(
      value = {@ApiResponse(responseCode = "200", description = "Pinnable org-unit options")})
  public List<OrgUnitMembershipOptionDto> getPinnableOrgUnits(@AuthenticationPrincipal Jwt jwt) {
    return authHelperService.isAdmin()
        ? orgUnitMembershipQueryService.listAllPinnableOptions()
        : orgUnitMembershipQueryService.listPickerOptionsWithDescendants(
            userService.getUserIdFromJwt(jwt));
  }

  /**
   * Returns everything the page layout needs in one read-only transaction: {@link
   * #getActiveOrgUnit()}, {@link #getPinnableOrgUnits(Jwt)}, {@link #getCapabilities()} and the
   * unread-notification count.
   *
   * <p>Answers match the individual endpoints; the call succeeds or fails as a whole (ADR-0151).
   *
   * @param jwt the caller's JWT; never {@code null} thanks to the class-level {@code @PreAuthorize}
   * @param callerId the caller's id as the notification endpoints resolve it
   * @return the caller's layout context; never {@code null}
   */
  @NotNull
  @GetMapping("/layout")
  @Transactional(readOnly = true)
  @Operation(
      summary = "The caller's layout context in one read",
      description =
          "Active org unit, pinnable org units, capability flags and unread-notification count -"
              + " the same answers as /me/active-org-unit, /me/org-units, /me/capabilities and"
              + " /notifications/unread-count, in one read-only transaction.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "The caller's layout context"),
        @ApiResponse(responseCode = "401", description = "Authentication required")
      })
  public LayoutResponse getLayout(@AuthenticationPrincipal Jwt jwt, @CurrentUserId UUID callerId) {
    return new LayoutResponse(
        getActiveOrgUnit().orgUnitId(),
        getPinnableOrgUnits(jwt),
        getCapabilities(),
        notificationService.unreadCount(callerId));
  }

  /**
   * Response for {@code GET /api/v1/me/active-org-unit}: the resolved effective org-unit context
   * for the current request. {@code null} means the admin is viewing all OrgUnits (or the user has
   * no assigned home Staffel and no pinned context).
   *
   * @param orgUnitId effective OrgUnit UUID, or {@code null}.
   */
  public record ActiveOrgUnitResponse(@Nullable UUID orgUnitId) {}

  /**
   * Response for {@code GET /api/v1/me/capabilities}: per-principal UI capability flags.
   *
   * @param canSeeBlueprintOverview whether the caller may open the org-unit blueprint overview
   *     (admin, officer or Spezialkommando lead)
   * @param canViewJobOrders whether the caller may enter the Job-Order area (admin or member of a
   *     profit-eligible org unit)
   * @param canViewOwnJobOrders whether the caller may view orders their own org unit requested
   *     (admin or any org-unit member; REQ-ORDERS-023)
   * @param canViewBankStaff whether the caller holds {@code BANK_EMPLOYEE} or above
   * @param canManageBank whether the caller holds {@code BANK_MANAGEMENT}
   * @param isLogisticianOrAbove whether the caller reaches {@code LOGISTICIAN} through the role
   *     hierarchy; gates the Lager and Auftrag write paths
   * @param isMissionManagerOrAbove whether the caller reaches {@code MISSION_MANAGER} through the
   *     role hierarchy; gates the payout confirmation
   * @param isAdmin whether the caller holds {@code ADMIN}
   */
  public record CapabilitiesResponse(
      boolean canSeeBlueprintOverview,
      boolean canViewJobOrders,
      boolean canViewOwnJobOrders,
      boolean canViewBankStaff,
      boolean canManageBank,
      boolean isLogisticianOrAbove,
      boolean isMissionManagerOrAbove,
      boolean isAdmin) {}

  /**
   * Response for {@code GET /api/v1/me/layout}: the four layout answers in one payload.
   *
   * @param activeOrgUnitId the effective org-unit context; {@code null} for admin "all org units"
   *     or no home Staffel
   * @param orgUnits the pinnable org units; never {@code null}, possibly empty
   * @param capabilities the caller's capability flags; never {@code null}
   * @param unreadNotifications the caller's unread-notification count
   */
  public record LayoutResponse(
      @Nullable UUID activeOrgUnitId,
      @NotNull List<OrgUnitMembershipOptionDto> orgUnits,
      @NotNull CapabilitiesResponse capabilities,
      long unreadNotifications) {}
}
