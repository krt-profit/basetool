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
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
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

  /** Answers role questions through the configured hierarchy rather than by literal match. */
  private final AuthHelperService authHelperService;

  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  private final UserService userService;

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
   *   <li>{@code isLogisticianOrAbove} / {@code isMissionManagerOrAbove} / {@code isAdmin} &mdash;
   *       the caller's <em>authorisation</em> standing, resolved through the role hierarchy. They
   *       exist because {@code UserDto}'s {@code isLogistician} / {@code isMissionManager} answer a
   *       different question: those are Staffel-membership projections and are {@code false} for an
   *       admin, who holds no Staffel membership by design. A client that gates on the membership
   *       flag therefore hides Lager, Auftrag and payout actions from admins and officers that the
   *       server would permit &mdash; which is exactly what happened (REQ-SEC-030).
   * </ul>
   *
   * @return the caller's UI capability flags; never {@code null}.
   */
  @GetMapping("/capabilities")
  @Operation(
      summary = "Per-principal UI capability flags (blueprint overview, job orders, bank staff).")
  public CapabilitiesResponse getCapabilities() {
    return new CapabilitiesResponse(
        ownerScopeService.canAccessBlueprintOverview(),
        ownerScopeService.canViewJobOrders(),
        ownerScopeService.canViewOwnJobOrders(),
        // Through the hierarchy on purpose: a Bankleitung holds BANK_MANAGEMENT and NOT
        // BANK_EMPLOYEE, so a direct check would hide the staff bank from the people who run it.
        authHelperService.hasReachableRole(Roles.authority(Roles.BANK_EMPLOYEE)),
        authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)),
        // Same reason, and the reason these three exist at all. UserDto's isLogistician /
        // isMissionManager are membership projections — resolveLogistician() reads the Staffel
        // rows and nothing else — so they are false for an ADMIN, who by design holds no Staffel
        // membership. A client gating on them hides actions from exactly the people most entitled
        // to perform them. These are the authorisation answer instead of the membership one.
        authHelperService.isLogisticianOrAbove(),
        authHelperService.hasReachableRole(Roles.authority(Roles.MISSION_MANAGER)),
        authHelperService.isAdmin());
  }

  /**
   * Returns the org units the caller may pin as their active context.
   *
   * <p><strong>One endpoint instead of one branch per client.</strong> The rule has two halves: an
   * admin may pin <em>any</em> active org unit, while everyone else may pin the units they belong
   * to <em>or reach through a Bereich or OL leadership seat</em>. Both clients had to know that,
   * and only one of them did — the web frontend branched on {@code isAdmin()} and the Android app
   * did not, so an admin (who by design holds no Staffel membership) was offered nothing but „Alle
   * Org-Einheiten" and could not narrow the app to a unit at all. Encapsulating the branch here
   * means a client asks one question and gets the right answer without reproducing the rule.
   *
   * <p><strong>All four kinds, not just Staffel and SK.</strong> A member may hold a seat on a
   * Bereich or on the Organisationsleitung and on nothing else, and those units own aggregates in
   * their own right (REQ-ORG-016: the create-time stamping applies no kind filter). Listing only
   * Staffeln and SKs left exactly those members with an empty switcher — the same shape of defect
   * as the admin one above, one tier up. The membership branch therefore reuses the drill-down
   * picker's reach, which resolves a leadership seat to the concrete units below it (REQ-ORG-015 —
   * never an admin-all marker), and the admin branch lists every active unit of every kind so an
   * admin can still reach at least as far as an OL member.
   *
   * <p>Both branches sort top-down (OL &rarr; Bereich &rarr; Staffel &rarr; SK, then by name), so
   * the two clients and the two branches render one order.
   *
   * <p>Carries no PII — org-unit names, shorthands and kinds only.
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
   * @param canViewBankStaff {@code true} iff the caller may reach the bank's staff surface at all
   *     &mdash; {@code BANK_EMPLOYEE} or anything above it in the role hierarchy.
   * @param canManageBank {@code true} iff the caller additionally holds {@code BANK_MANAGEMENT},
   *     which is what gates the account lifecycle and the grants matrix.
   * @param isLogisticianOrAbove {@code true} iff the caller reaches {@code LOGISTICIAN} through the
   *     role hierarchy &mdash; so {@code LOGISTICIAN}, {@code OFFICER} and {@code ADMIN} alike.
   *     This is the flag a client gates the Lager and the Auftrag write paths on. It is
   *     deliberately <em>not</em> {@code UserDto.isLogistician()}, which is a Staffel-membership
   *     projection and is {@code false} for an admin.
   * @param isMissionManagerOrAbove {@code true} iff the caller reaches {@code MISSION_MANAGER}
   *     through the hierarchy &mdash; {@code MISSION_MANAGER}, {@code OFFICER}, {@code ADMIN}. The
   *     flag behind the Operation's payout confirmation; the same membership-versus-authorisation
   *     distinction applies as above.
   * @param isAdmin {@code true} iff the caller holds {@code ADMIN}. Clients use it for the surfaces
   *     where admin is not merely "above" a role but a different scope altogether &mdash; an admin
   *     sees every org unit rather than their own memberships.
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
}
