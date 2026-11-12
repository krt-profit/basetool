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
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyHolderCandidate;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyKind;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyMovementPreview;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyOutcome;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneySettlements;
import de.greluc.krt.profit.basetool.backend.joborder.internal.SettleJobOrderMoneyDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Records actual settlement through the existing movement action. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders/{id}/money/movements/settlement")
@Tag(name = "Orders")
public class JobOrderMoneySettlementController {

  private final JobOrderMoneySettlements settlements;

  /**
   * Previews the principal, fees and funding for the selected settlement purpose.
   *
   * @param id the order
   * @param dto the chosen terms and observed revisions
   * @return exact cost and settlement effect
   */
  @PostMapping("/preview")
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "previewJobOrderMoneySettlement", summary = "Preview aUEC settlement")
  public JobOrderMoneyMovementPreview preview(
      @PathVariable UUID id, @Valid @RequestBody SettleJobOrderMoneyDto dto) {
    return settlements.preview(id, dto);
  }

  /**
   * Records or recovers an actual settlement.
   *
   * @param id the order
   * @param dto the submitted terms and observed revisions
   * @return the accepted result
   */
  @PostMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "settleJobOrderMoney", summary = "Record aUEC settlement")
  public JobOrderMoneyOutcome record(
      @PathVariable UUID id, @Valid @RequestBody SettleJobOrderMoneyDto dto) {
    return settlements.settle(id, dto).orElseThrow(MoneyProvisionRefusedException::new);
  }

  /**
   * Finds selectable accounts for an authorized beneficiary payment or requester return.
   *
   * @param id the order
   * @param kind the settlement purpose
   * @param sourceHolderId the holder funding a beneficiary payment
   * @param recipientId the requester designation making a return
   * @param q the optional account-name fragment
   * @return matching active account references
   */
  @GetMapping("/candidates")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "findJobOrderMoneySettlementCandidates",
      summary = "Find aUEC settlement accounts")
  public List<JobOrderMoneyHolderCandidate> candidates(
      @PathVariable UUID id,
      @RequestParam JobOrderMoneyKind kind,
      @RequestParam(required = false) @Nullable UUID sourceHolderId,
      @RequestParam(required = false) @Nullable UUID recipientId,
      @RequestParam(required = false) @Nullable @Size(max = 200) String q) {
    return settlements.candidates(id, kind, sourceHolderId, recipientId, q);
  }
}
