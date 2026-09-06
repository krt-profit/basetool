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
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only REST surface for the org-unit hierarchy ({@link
 * de.greluc.krt.profit.basetool.backend.model.OrgUnit}). Lives at {@code /api/v1/org-units} and
 * today exposes a single endpoint — the active-options list that the R5.d.c Job Order create form's
 * owner-picker consumes.
 *
 * <p>The endpoint complements {@code /api/v1/users/{id}/memberships} (introduced in R5.d.a):
 *
 * <ul>
 *   <li>{@code /users/{id}/memberships} returns the picker options bounded by a specific user's
 *       memberships. Used by aggregate-creation forms whose owner row carries a Staffel membership
 *       (inventory create, refinery-order create).
 *   <li>{@code /org-units/active} returns every active org unit, irrespective of any user's
 *       memberships. Used by the Job Order create form because Job Orders are cross-staffel
 *       workspaces — any active org unit may be the {@code requestingOrgUnitId} of a new order.
 * </ul>
 *
 * <p>Both endpoints return the same {@link OrgUnitMembershipOptionDto} wire shape so the picker
 * fragment can iterate them identically without branching on the source endpoint.
 */
@RestController
@RequestMapping("/api/v1/org-units")
@RequiredArgsConstructor
@Tag(name = "org-unit-controller", description = "Read-only org-unit hierarchy endpoints")
public class OrgUnitController {

  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  /**
   * Lists every active org unit (Staffel + Spezialkommando) as picker options, each carrying its
   * {@code isProfitEligible} flag. {@code permitAll}: the Job Order create form is reachable
   * anonymously (the public request form), so an unauthenticated guest must be able to fill both
   * the requesting picker (all options) and the responsible picker (the profit-eligible subset)
   * from this single fetch. The response reveals only public attributes (name + shorthand + kind +
   * profit flag) — no PII — mirroring the already-{@code permitAll} {@code /api/v1/squadrons}
   * catalog, and Job Order creation already exposes the requesting/responsible org unit on the
   * wire.
   *
   * @return picker options sorted Staffel-first then Spezialkommandos alphabetical.
   */
  @GetMapping("/active")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  @Operation(
      summary = "List every active org unit",
      description =
          "Returns the full active Staffel + Spezialkommando catalog as picker options, each with"
              + " its isProfitEligible flag. Drives the Job Order create form's owner-pickers — Job"
              + " Orders are cross-staffel workspaces and the picker must offer the full active"
              + " list, not the caller's memberships. Open to anonymous callers because the request"
              + " form is public; the payload carries no PII.")
  @ApiResponses(
      value = {@ApiResponse(responseCode = "200", description = "Active org-unit options")})
  public List<OrgUnitMembershipOptionDto> listActiveOrgUnits() {
    return orgUnitMembershipQueryService.listAllActiveOptions();
  }

  /**
   * Lists every active org unit of <em>all four</em> kinds (Staffel + Spezialkommando + Bereich +
   * Organisationsleitung) as picker options (epic #692 Phase 6, REQ-ORG-019). Drives the
   * bank-management account-create form, which links an {@code AREA} account to its Bereich and the
   * {@code CARTEL} account to the Organisationsleitung. {@code isAuthenticated}: unlike the public
   * {@link #listActiveOrgUnits()} (whose Staffel/SK-only list backs the anonymous Job-Order form),
   * this surfaces the Bereich/OL tiers and is gated to authenticated callers — the consumer is the
   * BANK_MANAGEMENT create form; the payload carries no PII.
   *
   * @return active org-unit options across all four kinds, grouped by tier.
   */
  @GetMapping("/active-all-kinds")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  @Operation(
      summary = "List every active org unit of all kinds (incl. Bereich + OL)",
      description =
          "Returns active Staffeln, Spezialkommandos, Bereiche and the Organisationsleitung as"
              + " picker options. Drives the bank-management create form that links an AREA account"
              + " to its Bereich and the CARTEL account to the OL. Authenticated; carries no PII.")
  @ApiResponses(
      value = {@ApiResponse(responseCode = "200", description = "Active org-unit options")})
  public List<OrgUnitMembershipOptionDto> listActiveOrgUnitsAllKinds() {
    return orgUnitMembershipQueryService.listAllActiveOrgUnitOptionsAllKinds();
  }
}
