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
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Gateway mirror of the backend's {@code RefineryExtractDto}, the {@code RefineryExtract} contract
 * v1 (ADR-0008), validated at the edge and relayed unchanged (REQ-INGEST-001).
 *
 * @param schemaVersion contract version; only {@code 1} is processed by the backend
 * @param tool producing tool identifier (provenance only)
 * @param toolVersion producing tool version (provenance only)
 * @param model VLM model identifier (provenance only)
 * @param generatedAt extraction timestamp (provenance only)
 * @param clientLanguage extractor UI language tag (provenance only)
 * @param orders the extracted orders; non-empty, at most five, only the first is processed
 */
public record RefineryExtractDto(
    @NotNull Integer schemaVersion,
    @Size(max = 100) String tool,
    @Size(max = 50) String toolVersion,
    @Size(max = 100) String model,
    Instant generatedAt,
    @Size(max = 16) String clientLanguage,
    @NotEmpty @Size(max = 5) List<@NotNull @Valid RefineryExtractOrderDto> orders) {}
