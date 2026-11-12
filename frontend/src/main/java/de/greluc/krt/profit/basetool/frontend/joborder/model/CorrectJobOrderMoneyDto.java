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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A connected set of revisions to accepted original money operations.
 *
 * @param operationId identity of the submitted correction
 * @param entries changed original facts
 * @param holderVersions observed revisions of the current affected holders
 * @param targetVersion observed target when the correction affects net handover
 */
@DtoMirror
public record CorrectJobOrderMoneyDto(
    @NotNull UUID operationId,
    @NotNull @Size(min = 1, max = 50) List<@NotNull @Valid Entry> entries,
    @NotNull @Size(max = 100) Map<UUID, @NotNull Long> holderVersions,
    @Nullable Long targetVersion) {

  /**
   * The correct amount of one immutable original fact.
   *
   * @param originalOperationId submitted identity of the original operation
   * @param amount correct net principal or provision, including zero
   * @param provision correct extra provision when the original was a transfer
   * @param removeAttribution whether the original commitment attribution was incorrect
   */
  public record Entry(
      @NotNull UUID originalOperationId,
      @NotBlank String amount,
      @Nullable String provision,
      boolean removeAttribution) {}
}
