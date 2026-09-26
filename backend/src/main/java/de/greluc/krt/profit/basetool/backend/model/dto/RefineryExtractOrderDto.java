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

package de.greluc.krt.profit.basetool.backend.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One refinement order read from SETUP screenshots; {@code raw*} fields are verbatim reads that the
 * import resolves against master data, reporting failures as draft issues.
 *
 * @param panelType screen tab of the capture; only {@code SETUP} is accepted
 * @param quoted {@code false} when captured before GET QUOTE; {@code null} counts as quoted
 * @param layoutConfidence derived layout-parse confidence in {@code [0,1]}
 * @param rawLocationName refinery location verbatim; {@code null} on pre-cropped input
 * @param rawMethodName refining method verbatim; nullable
 * @param rawInManifestTotal panel {@code IN MANIFEST} total, a nullable checksum over all rows
 * @param rawToRefineTotal panel {@code TO REFINE} total, a nullable checksum over refine-on rows
 * @param expenses total order cost in aUEC; {@code null} when unquoted
 * @param durationMinutes processing time in minutes; nullable
 * @param totalYieldScu PROCESSING-only figure; ignored in v1
 * @param sourceImages screenshots the order was stitched from; 1 to 50 (REQ-INGEST-011)
 * @param goods deduplicated material rows in on-screen order; 1 to 100
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
