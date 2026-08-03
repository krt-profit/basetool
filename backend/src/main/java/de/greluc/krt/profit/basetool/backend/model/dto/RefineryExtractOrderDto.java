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
 * One refinement order read from SETUP screenshot(s) — the {@code orders[]} element of the frozen
 * {@code RefineryExtract} contract (plan §5). All {@code raw*} fields are verbatim screen reads;
 * the import service resolves them against master data and reports failures as draft issues instead
 * of rejecting the request.
 *
 * @param panelType screen tab the capture shows: {@code SETUP} | {@code PROCESSING} | {@code
 *     UNKNOWN}; v1 accepts only {@code SETUP} (anything else is an envelope-level 400)
 * @param quoted {@code false} when the capture was taken before pressing GET QUOTE (the YIELD /
 *     cost / time cells still render {@code "--"}); {@code null} is treated as quoted
 * @param layoutConfidence derived layout-parse confidence in {@code [0,1]} (provenance/display)
 * @param rawLocationName refinery location verbatim from the terminal header — the header sits
 *     outside the work-order panel, so this is always {@code null} on pre-cropped input
 * @param rawMethodName refining method verbatim as read, e.g. {@code "FERRON EXCHANGE"}; nullable
 * @param rawInManifestTotal panel-header {@code IN MANIFEST} total; nullable completeness checksum
 *     (reconciled against the sum of all row quantities, never copied into the draft)
 * @param rawToRefineTotal panel-header {@code TO REFINE} total; nullable completeness checksum
 *     (reconciled against the sum of refine-ON row quantities, never copied into the draft)
 * @param expenses total order cost in aUEC; {@code null} when un-quoted
 * @param durationMinutes processing time in minutes (from e.g. {@code "20h 58m"}); nullable
 * @param totalYieldScu PROCESSING-only figure; always {@code null} on SETUP and ignored in v1
 * @param sourceImages screenshots this order was stitched from (provenance only); non-empty, at
 *     most 50 — a genuine extraction always has at least one capture, so an empty list marks a
 *     payload no real extraction produced (ADR-0008 amendment, REQ-INGEST-011)
 * @param goods stitched, deduplicated material rows in on-screen order; non-empty, at most 100 — an
 *     order with no rows carries nothing to import (ADR-0008 amendment)
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
