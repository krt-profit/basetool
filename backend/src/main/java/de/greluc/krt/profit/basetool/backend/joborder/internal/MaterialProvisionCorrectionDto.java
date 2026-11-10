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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryRowVersionDto;
import de.greluc.krt.profit.basetool.backend.kernel.QualityValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A scoped correction of false original attribution, quality or known physical quantity.
 *
 * @param kind the false fact being corrected
 * @param version the observed provision version
 * @param claimVersion the observed original contribution version
 * @param amount truthful credited attribution or physical provision quantity
 * @param targetClaimId an existing corrected attribution, or null to remove it
 * @param targetClaimVersion the observed corrected contribution version
 * @param stockVersions observed connected stock versions for a physical correction
 * @param targetStockRowId retained connected row selected for a truthful upward correction
 * @param suppliedQuality the explicitly corrected original quality
 * @param qualityStockRowIds consciously selected current rows whose known receipt portion is wrong
 */
public record MaterialProvisionCorrectionDto(
    @NotNull Kind kind,
    @NotNull Long version,
    @NotNull Long claimVersion,
    @NotNull @PositiveOrZero Double amount,
    @Nullable UUID targetClaimId,
    @Nullable Long targetClaimVersion,
    @NotNull List<@Valid InventoryRowVersionDto> stockVersions,
    @Nullable UUID targetStockRowId,
    @Nullable @QualityValue Integer suppliedQuality,
    List<UUID> qualityStockRowIds) {
  /** Freezes the current-stock selection without an implicit choice. */
  public MaterialProvisionCorrectionDto {
    qualityStockRowIds = qualityStockRowIds == null ? List.of() : List.copyOf(qualityStockRowIds);
  }

  /** The distinct ownership boundaries of connected false facts. */
  public enum Kind {
    /** Only the historically credited Staffel is wrong. */
    ATTRIBUTION,
    /** The original physical provision quantity is wrong. */
    QUANTITY,
    /** The original supplied quality is wrong. */
    QUALITY
  }
}
