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
import de.greluc.krt.profit.basetool.backend.joborder.internal.CorrectJobOrderMoneyDto;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyCorrectionPreview;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyCorrections;
import de.greluc.krt.profit.basetool.backend.joborder.internal.JobOrderMoneyOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Corrects linked original financial facts through the order's history. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/orders/{id}/money/corrections")
@Tag(name = "Orders")
public class JobOrderMoneyCorrectionController {

  private final JobOrderMoneyCorrections corrections;

  /**
   * Previews the effective result of a connected correction.
   *
   * @param id the order
   * @param dto the connected revised terms
   * @return before and after amounts and the resulting order figures
   */
  @PostMapping("/preview")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      operationId = "previewJobOrderMoneyCorrection",
      summary = "Preview a connected aUEC correction")
  public JobOrderMoneyCorrectionPreview preview(
      @PathVariable UUID id, @Valid @RequestBody CorrectJobOrderMoneyDto dto) {
    return corrections.preview(id, dto);
  }

  /**
   * Records or recovers an accepted correction.
   *
   * @param id the order
   * @param dto the operation identity, connected terms and affected versions
   * @return the accepted outcome and linked facts
   */
  @PostMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(operationId = "correctJobOrderMoney", summary = "Record a connected aUEC correction")
  public JobOrderMoneyOutcome record(
      @PathVariable UUID id, @Valid @RequestBody CorrectJobOrderMoneyDto dto) {
    return corrections.record(id, dto).orElseThrow(MoneyProvisionRefusedException::new);
  }
}
