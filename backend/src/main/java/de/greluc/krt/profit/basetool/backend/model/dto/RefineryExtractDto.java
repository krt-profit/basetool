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
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Envelope of the {@code RefineryExtract} v1 JSON contract produced by the desktop extractor.
 *
 * <p>Only {@code schemaVersion == 1} is accepted. Provenance fields are for display only and never
 * influence matching.
 *
 * @param schemaVersion contract version; must equal {@code 1}, checked in the service
 * @param tool producer name (provenance only)
 * @param toolVersion producer version (provenance only)
 * @param model VLM that produced the extract (provenance only)
 * @param generatedAt UTC instant of production (provenance only)
 * @param clientLanguage SC client language of the screenshots; always {@code "en"} in v1
 * @param orders extracted orders; only {@code orders[0]} is processed, surplus is flagged {@code
 *     MULTIPLE_ORDERS_TRUNCATED}
 */
public record RefineryExtractDto(
    @NotNull Integer schemaVersion,
    @Size(max = 100) String tool,
    @Size(max = 50) String toolVersion,
    @Size(max = 100) String model,
    Instant generatedAt,
    @Size(max = 16) String clientLanguage,
    @NotEmpty @Size(max = 5) List<@NotNull @Valid RefineryExtractOrderDto> orders) {}
