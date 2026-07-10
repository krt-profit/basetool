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

import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only echo of the org-unit context that the backend currently applies to staffel-scoped
 * queries. The active org-unit preference is owned by the frontend (Redis-backed Spring Session via
 * {@code MeFrontendController}); the backend learns about the caller's choice on every API call
 * through the {@code X-Active-Org-Unit-Id} header relayed by the frontend's WebClient.
 *
 * <p>This controller used to expose {@code PUT}/{@code DELETE} mutators that stored the selection
 * in the backend's {@code HttpSession}, but that was effectively a no-op: REST calls from the
 * frontend do not relay session cookies (only the OAuth2 bearer token), so each call created a
 * fresh backend session and the attribute was lost between requests. The mutators are gone; the
 * only remaining surface is {@code GET /active-org-unit} which reflects what the header for the
 * current request says, plus the per-principal {@code GET /capabilities} UI flags.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MeController {

  private final OwnerScopeService ownerScopeService;

  /**
   * Returns the org-unit context that the backend currently applies to staffel-scoped queries for
   * this request. For admins this is the {@code X-Active-Org-Unit-Id} header value relayed by the
   * frontend; for non-admins with a pinned context the header is honoured iff the pin matches one
   * of their memberships; otherwise this is the user's persistent home Staffel. The {@code
   * orgUnitId} is {@code null} when the admin is in "all OrgUnits" mode or the user has no assigned
   * home Staffel.
   *
   * @return current effective org-unit context for the calling request; never {@code null}.
   */
  @GetMapping("/active-org-unit")
  public ActiveOrgUnitResponse getActiveOrgUnit() {
    return new ActiveOrgUnitResponse(ownerScopeService.currentOrgUnitId().orElse(null));
  }

  /**
   * Per-principal UI capability flags the frontend uses to decide which optional menu entries to
   * show and which pages to redirect away from. Three flags today:
   *
   * <ul>
   *   <li>{@code canSeeBlueprintOverview} — whether the caller may open the org-unit blueprint
   *       availability overview (#364): {@code true} for admins, officers, and Spezialkommando
   *       leads. Reuses the exact gate the {@code /api/v1/personal-blueprints/overview} endpoints
   *       are class-gated by.
   *   <li>{@code canViewJobOrders} — whether the caller may enter the Job-Order area: {@code true}
   *       for admins and members of any profit-eligible org unit. Mirrors the backend gate folded
   *       into {@code OwnerScopeService.canSeeJobOrder} + the order-list short-circuit, so the
   *       hidden menu / redirect and the empty-list / 403 API stay in lockstep.
   *   <li>{@code canViewOwnJobOrders} — whether the caller may view the orders their own org unit
   *       requested (the "Meine Auftr&auml;ge" requester capability, REQ-ORDERS-023): {@code true}
   *       for admins and any member of at least one org unit, independent of profit eligibility. It
   *       lets a non-profit ordering-squad member reach their own placed orders instead of being
   *       redirected to the create form.
   * </ul>
   *
   * @return the caller's UI capability flags; never {@code null}.
   */
  @GetMapping("/capabilities")
  @Operation(summary = "Per-principal UI capability flags (blueprint-overview + job-order access).")
  public CapabilitiesResponse getCapabilities() {
    return new CapabilitiesResponse(
        ownerScopeService.canAccessBlueprintOverview(),
        ownerScopeService.canViewJobOrders(),
        ownerScopeService.canViewOwnJobOrders());
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
   * @param canSeeBlueprintOverview {@code true} iff the caller may open the org-unit blueprint
   *     availability overview (admin, officer, or Spezialkommando lead).
   * @param canViewJobOrders {@code true} iff the caller may enter the Job-Order area (admin, or
   *     member of at least one profit-eligible org unit).
   * @param canViewOwnJobOrders {@code true} iff the caller may view the orders their own org unit
   *     requested (admin, or member of at least one org unit), independent of profit eligibility
   *     (REQ-ORDERS-023).
   */
  public record CapabilitiesResponse(
      boolean canSeeBlueprintOverview, boolean canViewJobOrders, boolean canViewOwnJobOrders) {}
}
