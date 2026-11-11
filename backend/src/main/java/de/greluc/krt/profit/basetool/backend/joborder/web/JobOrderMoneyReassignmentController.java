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

import de.greluc.krt.profit.basetool.backend.joborder.api.MoneyProvisionRefusedException;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyOutcome;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyReassignment;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyReassignmentDestination;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyReassignmentPreview;
import de.greluc.krt.profit.basetool.backend.joborder.internal.ReassignJobOrderMoneyDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Existing movement action for reassigning reported money between order purposes. */
@RestController
@RequestMapping("/api/v1/orders/{id}/money/movements/reassignment")
@RequiredArgsConstructor
@Tag(name = "Orders")
public class JobOrderMoneyReassignmentController {

  private final JobOrderMoneyReassignment reassignment;

  /**
   * Finds a permitted active aUEC order by its displayed number.
   *
   * @param id the source order
   * @param sourceHolderId the holder moving funds
   * @param number the destination display number
   * @return the destination identity and relevant holder revisions
   */
  @GetMapping("/destination")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "findJobOrderMoneyReassignmentDestination",
      summary = "Find a permitted purpose reassignment destination")
  public JobOrderMoneyReassignmentDestination destination(
      @PathVariable UUID id, @RequestParam UUID sourceHolderId, @RequestParam @Min(1) int number) {
    return reassignment.destination(id, sourceHolderId, number);
  }

  /**
   * Previews a purpose change for both readable orders and their exact holder revisions.
   *
   * @param id the source order
   * @param dto the proposed terms
   * @return the exact reassignment effects
   */
  @PostMapping("/preview")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "previewJobOrderMoneyReassignment",
      summary = "Preview an aUEC purpose reassignment")
  public JobOrderMoneyReassignmentPreview preview(
      @PathVariable UUID id, @Valid @RequestBody ReassignJobOrderMoneyDto dto) {
    return reassignment.preview(id, dto);
  }

  /**
   * Records both purpose changes together or replays their matching submitted identity.
   *
   * @param id the source order
   * @param dto the submitted terms and revisions
   * @return the accepted source outcome
   */
  @PostMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "recordJobOrderMoneyReassignment",
      summary = "Record an aUEC purpose reassignment")
  public JobOrderMoneyOutcome record(
      @PathVariable UUID id, @Valid @RequestBody ReassignJobOrderMoneyDto dto) {
    return reassignment.record(id, dto).orElseThrow(MoneyProvisionRefusedException::new);
  }
}
