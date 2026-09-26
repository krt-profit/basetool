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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code SyncReportDto}, read by {@code
 * AdminSyncReportsPageController}; must match it field for field.
 *
 * @param id event id
 * @param runId run id grouping a sync cycle's events
 * @param ranAt event timestamp (UTC)
 * @param sourceSystem catalogue name ({@code "UEX"} or {@code "SCWIKI"})
 * @param eventType event-type name
 * @param aggregate aggregate label
 * @param externalUuid external asset UUID, or {@code null}
 * @param externalId external integer id, or {@code null}
 * @param externalName external display name, or {@code null}
 * @param detail free-form detail
 */
public record SyncReportDto(
    UUID id,
    UUID runId,
    Instant ranAt,
    String sourceSystem,
    String eventType,
    String aggregate,
    UUID externalUuid,
    Integer externalId,
    String externalName,
    String detail) {}
