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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.CreateClaimDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.MaterialClaimService;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface over material claims ("Eintragungen") nested under a job order, letting profit
 * squadrons sign up for partial quantities of a material bucket on a Spezialkommando order.
 *
 * <p>Reads require {@code canSeeJobOrder}; writes require LOGISTICIAN or above and {@code
 * canViewJobOrders}. The fine-grained permissions and claim invariants are enforced in {@link
 * MaterialClaimService}.
 */
@RestController
@RequestMapping("/api/v1/orders/{jobOrderId}/claims")
@RequiredArgsConstructor
@Tag(
    name = "Material Claims",
    description = "Squadron claims on a Spezialkommando order's materials")
public class MaterialClaimController {

  private final MaterialClaimService materialClaimService;

  /**
   * Returns the per-bucket claim view of an order: required vs. claimed vs. open-remaining plus the
   * individual squadron claims. Visible to anyone who may see the order.
   *
   * @param jobOrderId the order id.
   * @return one entry per required material bucket.
   */
  @GetMapping
  @Operation(
      summary = "List claim buckets for an order",
      description =
          "Returns each required material bucket with its required / claimed / open-remaining"
              + " amounts and the individual squadron claims.")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#jobOrderId)")
  @Transactional(readOnly = true)
  public List<ClaimBucketDto> getClaimBuckets(@PathVariable UUID jobOrderId) {
    return materialClaimService.getClaimBuckets(jobOrderId);
  }

  /**
   * Lists the current caller's writable claiming squadrons for a readable order.
   *
   * @param jobOrderId the order id
   * @return current claiming options, empty when claim writes are unavailable
   */
  @GetMapping("/claiming-options")
  @Operation(
      summary = "List writable claiming squadrons for an order",
      description =
          "Returns eligible squadrons allowed by the current claim-write authority."
              + " Terminal, invalid or non-SK orders return an empty list.")
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#jobOrderId)")
  @Transactional(readOnly = true)
  public List<OrgUnitMembershipOptionDto> claimingOptions(@PathVariable UUID jobOrderId) {
    return materialClaimService.claimingOptions(jobOrderId);
  }

  /**
   * Creates or updates the calling context's claim for a bucket, upserting on {@code (bucket,
   * squadron)}.
   *
   * @param jobOrderId the order id.
   * @param dto the claim payload.
   * @return the persisted claim.
   */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Create or update a material claim",
      description =
          "Creates or updates a partial material promise of an active squadron in the actual Profit"
              + " hierarchy on a nonterminal SK order. Current responsibility and claiming-unit"
              + " eligibility are required for every write. Bucket, overclaim and contextual writer"
              + " checks apply.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Claim created or updated"),
    @ApiResponse(
        responseCode = "400",
        description =
            "Invalid responsibility or claiming unit, terminal/non-SK order, unknown bucket, or"
                + " overclaim"),
    @ApiResponse(responseCode = "403", description = "Forbidden – may not act for this squadron"),
    @ApiResponse(responseCode = "404", description = "Order not found")
  })
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "') and @requestScopeResolver.canViewJobOrders()")
  public ClaimDto upsertClaim(
      @PathVariable UUID jobOrderId, @RequestBody @Valid CreateClaimDto dto) {
    return materialClaimService.upsertClaim(jobOrderId, dto);
  }

  /**
   * Withdraws a single claim. Same permission and order-state gates as {@link #upsertClaim}.
   *
   * @param jobOrderId the order id.
   * @param claimId the claim to withdraw.
   * @param version the observed contribution version; missing or stale values conflict.
   */
  @DeleteMapping("/{claimId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Withdraw a material claim",
      description =
          "Ends one versioned material promise while preserving support and contribution history.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Claim withdrawn"),
    @ApiResponse(responseCode = "400", description = "Order is terminal or no longer an SK order"),
    @ApiResponse(responseCode = "403", description = "Forbidden – may not act for this squadron"),
    @ApiResponse(responseCode = "404", description = "Order or claim not found"),
    @ApiResponse(responseCode = "409", description = "Missing or stale contribution version")
  })
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "') and @requestScopeResolver.canViewJobOrders()")
  public void withdrawClaim(
      @PathVariable UUID jobOrderId,
      @PathVariable UUID claimId,
      @RequestParam(required = false) Long version) {
    materialClaimService.withdrawClaim(jobOrderId, claimId, version);
  }
}
