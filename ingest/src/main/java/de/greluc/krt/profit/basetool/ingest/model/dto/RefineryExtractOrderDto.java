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

package de.greluc.krt.profit.basetool.ingest.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One extracted refinery order within a {@link RefineryExtractDto}. Mirror of the backend record;
 * only verbatim screen reads — the backend resolves names against master data.
 *
 * @param panelType the panel the capture came from; the backend requires {@code SETUP}
 * @param quoted whether the order was quoted on screen (drives the un-quoted warning)
 * @param layoutConfidence per-order layout-detection confidence in [0,1]
 * @param rawLocationName verbatim refinery-location read, resolved backend-side
 * @param rawMethodName verbatim refining-method read, resolved backend-side
 * @param rawInManifestTotal the IN MANIFEST header total (never validated by the backend)
 * @param rawToRefineTotal the TO REFINE header total (drives the checksum warning)
 * @param expenses the order expenses read from screen
 * @param durationMinutes the refining duration in minutes
 * @param totalYieldScu the total yield in SCU read from screen
 * @param sourceImages provenance for the stitched capture(s); non-empty, at most 50 — a genuine
 *     extraction is always stitched from at least one screenshot, so an empty list marks a payload
 *     that no real capture produced (ADR-0008 amendment, REQ-INGEST-011)
 * @param goods the per-row reads; non-empty, at most 100 — an order with no rows carries nothing to
 *     import (ADR-0008 amendment)
 */
public record RefineryExtractOrderDto(
    @NotNull @Size(max = 32) String panelType,
    Boolean quoted,
    @DecimalMin("0.0") @DecimalMax("1.0") Double layoutConfidence,
    @Size(max = 255) String rawLocationName,
    @Size(max = 255) String rawMethodName,
    @PositiveOrZero Long rawInManifestTotal,
    @PositiveOrZero Long rawToRefineTotal,
    @PositiveOrZero @DecimalMax("1000000000.0") Double expenses,
    @PositiveOrZero Long durationMinutes,
    @PositiveOrZero Double totalYieldScu,
    @NotEmpty @Size(max = 50) List<@NotNull @Valid RefineryExtractImageDto> sourceImages,
    @NotEmpty @Size(max = 100) List<@NotNull @Valid RefineryExtractGoodDto> goods) {}
