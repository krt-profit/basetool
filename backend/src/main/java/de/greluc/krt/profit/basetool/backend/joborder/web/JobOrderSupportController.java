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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderSupportDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyCommitmentCommands;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderSupportService;
import de.greluc.krt.profit.basetool.backend.joborder.internal.WriteMoneyCommitmentDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Serves each order's single Staffel participation workflow. */
@RestController
@RequestMapping("/api/v1/orders/{id}/participations")
@RequiredArgsConstructor
@Tag(name = "Order Support", description = "Staffel participation on job orders")
public class JobOrderSupportController {
  private final JobOrderSupportService service;
  private final JobOrderMoneyCommitmentCommands moneyCommitments;

  /**
   * Lists the order's Staffel participation and the current enrollment choices.
   *
   * @param id the readable order
   * @return the support view
   */
  @GetMapping
  @PreAuthorize("isAuthenticated() and @jobOrderAccessPolicy.canSeeJobOrder(#id)")
  @Operation(operationId = "getJobOrderSupport", summary = "List Staffel participation on an order")
  public JobOrderSupportDto support(@PathVariable UUID id) {
    return service.support(id);
  }

  /**
   * Starts support by an eligible Staffel.
   *
   * @param id the order receiving support
   * @param unitId the supporting Staffel
   */
  @PutMapping("/{unitId}")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(operationId = "enrollJobOrderSupport", summary = "Enroll a Staffel in an order")
  public void enroll(@PathVariable UUID id, @PathVariable UUID unitId) {
    service.enroll(id, unitId);
  }

  /**
   * Records or revises the unit's outstanding aUEC commitment.
   *
   * @param id the order receiving the contribution
   * @param unitId the responsible or supporting unit making the commitment
   * @param input the amount and observed commitment occurrence
   */
  @PutMapping("/{unitId}/money-commitment")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(operationId = "putJobOrderMoneyCommitment", summary = "Record a unit aUEC commitment")
  public void putMoneyCommitment(
      @PathVariable UUID id,
      @PathVariable UUID unitId,
      @Valid @RequestBody WriteMoneyCommitmentDto input) {
    moneyCommitments.put(id, unitId, input);
  }

  /**
   * Ends the observed Staffel support and its open promises.
   *
   * @param id the order receiving support
   * @param unitId the supporting Staffel
   * @param version the observed participation version
   */
  @DeleteMapping("/{unitId}")
  @PreAuthorize("isAuthenticated()")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      operationId = "withdrawJobOrderSupport",
      summary = "Withdraw a Staffel's support from an order")
  public void withdraw(
      @PathVariable UUID id,
      @PathVariable UUID unitId,
      @RequestParam(required = false) Long version) {
    service.withdraw(id, unitId, version);
  }
}
