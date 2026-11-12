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

import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyHolderCandidate;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyHolderIdentity;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyHolderIdentityDto;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Applies scoped identity corrections to existing order-money holders. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders/{id}/money/holders")
public class JobOrderMoneyHolderIdentityController {

  private final JobOrderMoneyHolderIdentity identity;

  /**
   * Corrects a holder name or link or deliberately merges duplicate identities.
   *
   * @param id the order
   * @param holder the holder being corrected
   * @param dto the chosen action and observed revisions
   */
  @PatchMapping("/{holder}/identity")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "changeJobOrderMoneyHolderIdentity",
      summary = "Correct an aUEC holder identity")
  public void change(
      @PathVariable UUID id,
      @PathVariable UUID holder,
      @Valid @RequestBody JobOrderMoneyHolderIdentityDto dto) {
    identity.change(id, holder, dto);
  }

  /**
   * Finds accounts available for explicit selection by the responsible writer.
   *
   * @param id the order
   * @param q optional name fragment
   * @return matching active accounts
   */
  @GetMapping("/identity-candidates")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "findJobOrderMoneyHolderIdentityCandidates",
      summary = "Find aUEC holder accounts")
  public List<JobOrderMoneyHolderCandidate> candidates(
      @PathVariable UUID id, @RequestParam(required = false) @Nullable @Size(max = 200) String q) {
    return identity.candidates(id, q);
  }
}
