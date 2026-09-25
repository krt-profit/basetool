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

import de.greluc.krt.profit.basetool.backend.model.dto.ClaimBucketDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ClaimDto;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateClaimDto;
import de.greluc.krt.profit.basetool.backend.service.MaterialClaimService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
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
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeJobOrder(#jobOrderId)")
  @Transactional(readOnly = true)
  public List<ClaimBucketDto> getClaimBuckets(@PathVariable UUID jobOrderId) {
    return materialClaimService.getClaimBuckets(jobOrderId);
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
          "Signs a profit-eligible squadron up for a partial quantity of a material bucket on a"
              + " Spezialkommando order, or updates its existing claim. Rejected on non-SK or"
              + " terminal orders, on overclaim, and when a new claim names a squadron that is not"
              + " profit-eligible.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Claim created or updated"),
    @ApiResponse(
        responseCode = "400",
        description = "Not an open SK order, unknown bucket, overclaim, or non-profit squadron"),
    @ApiResponse(responseCode = "403", description = "Forbidden – may not act for this squadron"),
    @ApiResponse(responseCode = "404", description = "Order not found")
  })
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "') and @ownerScopeService.canViewJobOrders()")
  public ClaimDto upsertClaim(
      @PathVariable UUID jobOrderId, @RequestBody @Valid CreateClaimDto dto) {
    return materialClaimService.upsertClaim(jobOrderId, dto);
  }

  /**
   * Withdraws a single claim. Same permission and order-state gates as {@link #upsertClaim}.
   *
   * @param jobOrderId the order id.
   * @param claimId the claim to withdraw.
   */
  @DeleteMapping("/{claimId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Withdraw a material claim",
      description = "Removes a squadron's claim from a bucket.")
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Claim withdrawn"),
    @ApiResponse(responseCode = "400", description = "Order is terminal or no longer an SK order"),
    @ApiResponse(responseCode = "403", description = "Forbidden – may not act for this squadron"),
    @ApiResponse(responseCode = "404", description = "Order or claim not found")
  })
  @PreAuthorize("hasRole('" + Roles.LOGISTICIAN + "') and @ownerScopeService.canViewJobOrders()")
  public void withdrawClaim(@PathVariable UUID jobOrderId, @PathVariable UUID claimId) {
    materialClaimService.withdrawClaim(jobOrderId, claimId);
  }
}
