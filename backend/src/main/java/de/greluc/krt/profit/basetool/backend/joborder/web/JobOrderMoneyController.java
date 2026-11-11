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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.CreateAuecJobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyCommands;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyHolderCandidate;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyOutcome;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyProvisionPreview;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyQueries;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyView;
import de.greluc.krt.profit.basetool.backend.joborder.internal.PreviewJobOrderMoneyProvisionDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.ProvideJobOrderMoneyDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Shared workspace endpoints for an aUEC order and its reported money. */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders")
public class JobOrderMoneyController {

  private final JobOrderMoneyCommands commands;
  private final JobOrderMoneyQueries queries;

  /**
   * Commissions an exact net amount through the existing unit writer authority.
   *
   * @param dto order creation terms
   * @return the new order
   */
  @PostMapping("/auec")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "createAuecJobOrder", summary = "Create an aUEC job order")
  public JobOrderDto createMoneyOrder(@Valid @RequestBody CreateAuecJobOrderDto dto) {
    return commands.create(dto);
  }

  /**
   * Returns the current monetary figures for an order the caller may read.
   *
   * @param id the order
   * @return the reported, purpose-bound money view
   */
  @GetMapping("/{id}/money")
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "getJobOrderMoney", summary = "Get reported aUEC order money")
  public JobOrderMoneyView getMoney(@PathVariable UUID id) {
    return queries.getMoney(id);
  }

  /**
   * Records or replays a provision using the supplied logical identity.
   *
   * @param id the order
   * @param dto the submitted provision
   * @return the stored result
   */
  @PostMapping("/{id}/money/provisions")
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "provideJobOrderMoney", summary = "Record aUEC order provision")
  public JobOrderMoneyOutcome provideMoney(
      @PathVariable UUID id, @Valid @RequestBody ProvideJobOrderMoneyDto dto) {
    return commands.provide(id, dto);
  }

  /**
   * Previews a provision with no financial or audit effect.
   *
   * @param id the order
   * @param dto the proposed provision
   * @return exact amount and the resulting reported holding at the submitted revision
   */
  @PostMapping("/{id}/money/provisions/preview")
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "previewJobOrderMoneyProvision", summary = "Preview aUEC provision")
  public JobOrderMoneyProvisionPreview previewMoney(
      @PathVariable UUID id, @Valid @RequestBody PreviewJobOrderMoneyProvisionDto dto) {
    return commands.preview(id, dto);
  }

  /**
   * Offers selectable accounts only within the current responsible writer's provision action.
   *
   * @param id the order
   * @param q optional name fragment
   * @return active account references
   */
  @GetMapping("/{id}/money/holder-candidates")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "findJobOrderMoneyHolderCandidates",
      summary = "Find aUEC holder accounts")
  public List<JobOrderMoneyHolderCandidate> holderCandidates(
      @PathVariable UUID id, @RequestParam(required = false) @Nullable @Size(max = 200) String q) {
    return queries.holderCandidates(id, q);
  }

  /**
   * Retrieves the durable result without rebooking a provision.
   *
   * @param id the order
   * @param operation the logical operation
   * @return the stored result if the attempt was accepted
   */
  @GetMapping("/{id}/money/operations/{operation}")
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "getJobOrderMoneyOutcome", summary = "Recover an aUEC operation outcome")
  public JobOrderMoneyOutcome getMoneyOutcome(@PathVariable UUID id, @PathVariable UUID operation) {
    return queries.getOutcome(id, operation);
  }
}
