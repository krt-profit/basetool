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
 * Read-only REST surface for the {@link de.greluc.krt.profit.basetool.backend.model.OrgUnit}
 * hierarchy, serving org-unit picker options.
 *
 * <p>Returns the same {@link OrgUnitMembershipOptionDto} shape as {@code /users/{id}/memberships},
 * but independent of any user's memberships.
 */
@RestController
@RequestMapping("/api/v1/org-units")
@RequiredArgsConstructor
@Tag(name = "org-unit-controller", description = "Read-only org-unit hierarchy endpoints")
public class OrgUnitController {

  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  /**
   * Lists every active Staffel and Spezialkommando as picker options, each with its {@code
   * isProfitEligible} flag, for the Job Order form's requesting and responsible pickers. Carries no
   * PII.
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
              + " list, not the caller's memberships. Members only since REQ-SEC-052; it was open"
              + " to anonymous callers while the request form was.")
  @ApiResponses(
      value = {@ApiResponse(responseCode = "200", description = "Active org-unit options")})
  public List<OrgUnitMembershipOptionDto> listActiveOrgUnits() {
    return orgUnitMembershipQueryService.listAllActiveOptions();
  }

  /**
   * Lists every active org unit of all four kinds as picker options (REQ-ORG-019), for the
   * bank-management account-create form that links an {@code AREA} account to its Bereich and the
   * {@code CARTEL} account to the Organisationsleitung. Carries no PII.
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
