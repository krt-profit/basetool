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
 * Envelope of the frozen {@code RefineryExtract} JSON contract (v1) produced by the desktop
 * extractor and consumed by {@code POST /api/v1/refinery-orders/import-extract} — see {@code
 * docs/archive/REFINERY_SCREENSHOT_IMPORT_PLAN.md} §5 (epic #439, Phase 1 #434). The provenance fields
 * ({@code tool}, {@code toolVersion}, {@code model}, {@code generatedAt}, {@code clientLanguage})
 * are echoed for display only and never influence matching.
 *
 * <p>Only {@code schemaVersion == 1} is accepted; the service rejects other versions with a 400 so
 * an outdated extractor fails loudly instead of producing a silently wrong draft. The {@code @Size}
 * caps are defensive limits on an authenticated endpoint, far above anything a real extract
 * produces (v1 emits exactly one order).
 *
 * @param schemaVersion contract version; must equal {@code 1} (enforced in the service, not via
 *     bean validation, so the error carries an i18n message instead of a generic 400)
 * @param tool producer name, e.g. {@code "basetool-sc-extractor"} (provenance only)
 * @param toolVersion producer version string (provenance only)
 * @param model VLM that produced the extract, e.g. {@code "qwen3-vl:8b-instruct"} (provenance only)
 * @param generatedAt UTC instant the extract was produced (provenance only)
 * @param clientLanguage SC client language the screenshots were taken in; v1 is always {@code "en"}
 * @param orders extracted orders; v1 producers emit exactly one, the service processes {@code
 *     orders[0]} and flags any surplus with {@code MULTIPLE_ORDERS_TRUNCATED}
 */
public record RefineryExtractDto(
    @NotNull Integer schemaVersion,
    @Size(max = 100) String tool,
    @Size(max = 50) String toolVersion,
    @Size(max = 100) String model,
    Instant generatedAt,
    @Size(max = 16) String clientLanguage,
    @NotEmpty @Size(max = 5) List<@NotNull @Valid RefineryExtractOrderDto> orders) {}
