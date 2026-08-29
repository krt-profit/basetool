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

import de.greluc.krt.profit.basetool.backend.model.dto.PromotionEligibilityResponse;
import de.greluc.krt.profit.basetool.backend.service.PromotionEligibilityService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for inspecting promotion eligibility.
 *
 * <p>The personal endpoints are filtered by JWT sub – callers never see another member's
 * eligibility through them. The {@code /user/{userId}} endpoint is restricted to {@code ADMIN} and
 * {@code OFFICER} so officers can drive promotion reviews from the management page.
 */
@RestController
@RequestMapping("/api/v1/promotion/eligibility")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(
    name = "Promotion Eligibility",
    description = "Evaluate whether a member meets the promotion requirements.")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class PromotionEligibilityController {

  private final PromotionEligibilityService service;

  /**
   * Returns the eligibility outcome for every configured rank transition for the calling user.
   *
   * @param ownerUserId the caller's {@code app_user.id}
   * @return one entry per configured transition, possibly empty
   */
  @GetMapping("/my")
  @Operation(summary = "Evaluate eligibility for the caller for every configured rank transition.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Per-transition eligibility for the caller."),
    @ApiResponse(responseCode = "401", description = "Authentication required.")
  })
  public List<PromotionEligibilityResponse> myEligibility(@CurrentUserId UUID ownerUserId) {
    return service.evaluateAllForUser(ownerUserId);
  }

  /**
   * Returns the eligibility outcome for one specific rank transition for the calling user. Useful
   * for the "next promotion" widget which only cares about the user's current rank.
   *
   * @param fromRank the rank the caller currently holds
   * @param toRank the rank the caller would be promoted to
   * @param ownerUserId the caller's {@code app_user.id}
   * @return the per-rule outcome plus an aggregate {@code eligible} flag
   */
  @GetMapping("/my/by-ranks")
  @Operation(summary = "Evaluate eligibility for the caller for a single rank transition.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Eligibility for the requested transition.")
  })
  public PromotionEligibilityResponse myEligibilityForRanks(
      @Parameter(description = "Current rank of the caller.") @RequestParam int fromRank,
      @Parameter(description = "Target rank for the promotion.") @RequestParam int toRank,
      @CurrentUserId UUID ownerUserId) {
    return service.evaluateForRanks(ownerUserId, fromRank, toRank);
  }

  /**
   * Officer/admin view: returns the eligibility outcome for every configured rank transition for an
   * arbitrary member.
   *
   * @param userId the {@code app_user.id} of the member to inspect
   * @return one entry per configured transition, possibly empty
   */
  @GetMapping("/user/{userId}")
  @PreAuthorize(Roles.ADMIN_OR_OFFICER)
  @Operation(summary = "Evaluate eligibility for another member (ADMIN/OFFICER only).")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Per-transition eligibility for the target member."),
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
  })
  public List<PromotionEligibilityResponse> eligibilityForUser(@PathVariable UUID userId) {
    return service.evaluateAllForUserAsAdmin(userId);
  }
}
