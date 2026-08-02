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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the gateway returns to the desktop extractor after a successful ingest: the unguessable
 * single-use handoff id, the draft kind, and the fully-formed frontend URL the extractor opens so
 * the browser lands on the pre-filled review form (REQ-INGEST-004).
 *
 * @param handoffId the opaque, unguessable id keying the staged draft in Redis
 * @param kind which draft was staged ({@code REFINERY} / {@code BLUEPRINT})
 * @param frontendUrl the absolute URL to open in the browser, already carrying {@code ?handoff=}
 */
@Schema(description = "The single-use handoff the extractor opens after a successful ingest.")
public record IngestResponseDto(
    @Schema(
            description =
                "Opaque, single-use id keying the staged draft. Treat it as a secret: it travels in"
                    + " the browser URL and is consumed on first read.",
            example = "0jUZ9uJ5pQ3vD1kR7tXwYbNcMeFg")
        String handoffId,
    @Schema(description = "Which draft was staged; selects the frontend pre-fill surface.")
        HandoffKind kind,
    @Schema(
            description =
                "Absolute frontend URL to open in the browser, already carrying ?handoff=<id>.",
            example = "https://app.profit-base.online/refinery-orders/create?handoff=0jUZ9uJ5")
        String frontendUrl) {}
