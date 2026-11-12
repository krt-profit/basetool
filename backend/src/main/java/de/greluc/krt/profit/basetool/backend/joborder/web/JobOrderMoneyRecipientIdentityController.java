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

import de.greluc.krt.profit.basetool.backend.joborder.internal.DesignateJobOrderMoneyRecipientDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyHolderCandidate;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyRecipientIdentity;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyRecipientView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Corrects a direct requester designation and retains linked payments. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders/{id}/money/recipients/{recipient}")
public class JobOrderMoneyRecipientIdentityController {

  private final JobOrderMoneyRecipientIdentity identity;

  /**
   * Corrects the account or name for the current direct designation.
   *
   * @param id the order
   * @param recipient the designation being corrected
   * @param dto the correct account or accountless name
   * @return the replacement designation
   */
  @PatchMapping("/identity")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "correctJobOrderMoneyRecipientIdentity",
      summary = "Correct an aUEC requester designation")
  public JobOrderMoneyRecipientView correct(
      @PathVariable UUID id,
      @PathVariable UUID recipient,
      @Valid @RequestBody DesignateJobOrderMoneyRecipientDto dto) {
    return identity.correct(id, recipient, dto);
  }

  /**
   * Finds eligible accounts for an explicit direct designation correction.
   *
   * @param id the order
   * @param recipient the direct designation
   * @param q optional account name fragment
   * @return matching active accounts
   */
  @GetMapping("/identity-candidates")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "findJobOrderMoneyRecipientIdentityCandidates",
      summary = "Find aUEC requester correction accounts")
  public List<JobOrderMoneyHolderCandidate> candidates(
      @PathVariable UUID id,
      @PathVariable UUID recipient,
      @RequestParam(required = false) @Nullable @Size(max = 200) String q) {
    return identity.candidates(id, recipient, q);
  }
}
