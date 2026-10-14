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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Stores the risk-class table (REQ-PUR-011): one entry per class to change.
 *
 * @param classes the classes with their new values and the echoed versions
 */
public record UpdateRiskClassesRequest(@NotEmpty @Valid List<UpdateRiskClassEntry> classes) {

  /**
   * One class to change.
   *
   * @param code the class
   * @param maxTotal the highest deal total in whole aUEC
   * @param maxAdvancePercent the share that may be paid in advance, 0 to 100
   * @param requiresKnownPartner whether the partner must have a settled deal
   * @param escortSuggested whether an escort is suggested
   * @param version the echoed optimistic-locking version
   */
  @Schema(name = "UpdateRiskClassEntry")
  public record UpdateRiskClassEntry(
      @NotNull RiskClassCode code,
      @NotNull @Positive Long maxTotal,
      @NotNull @Min(0) @Max(100) Integer maxAdvancePercent,
      boolean requiresKnownPartner,
      boolean escortSuggested,
      @NotNull Long version) {}
}
