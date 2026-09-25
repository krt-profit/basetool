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

package de.greluc.krt.profit.basetool.backend.model.dto.request;

import de.greluc.krt.profit.basetool.backend.validation.DtoConstraints;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Request DTO for the full-replace {@code PUT /api/v1/missions/{id}}: the fields of {@link
 * CreateMissionRequest} plus {@code actualStartTime} / {@code actualEndTime} and the global {@code
 * version}.
 *
 * <p>Server-managed fields are absent so they cannot be set via JSON. This path bumps all section
 * counters; the {@code /core}, {@code /schedule} and {@code /flags} patches are preferred.
 */
public record UpdateMissionRequest(
    @NotBlank @Size(max = 255) String name,
    @Nullable @Size(max = 20000) String description,
    @Nullable
        @Size(max = 2048)
        @Pattern(regexp = DtoConstraints.HTTPS_URL_REGEX, message = "must start with https://")
        String calendarLink,
    @Nullable @Size(max = 64) String status,
    @Nullable Instant meetingTime,
    @Nullable Instant plannedStartTime,
    @Nullable Instant plannedEndTime,
    @Nullable Instant actualStartTime,
    @Nullable Instant actualEndTime,
    @Nullable Boolean isInternal,
    @Nullable UUID operationId,
    @NotNull Long version,
    @Nullable @Size(max = 200) String meetingPoint) {}
