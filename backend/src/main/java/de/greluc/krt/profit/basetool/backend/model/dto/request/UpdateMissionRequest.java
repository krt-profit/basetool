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
 * Request DTO for {@code PUT /api/v1/missions/{id}} — the legacy full-replace update path. Carries
 * the same safe fields as {@link CreateMissionRequest} plus the {@code actualStartTime} / {@code
 * actualEndTime} lifecycle columns (the legacy PUT path is allowed to set those explicitly to
 * preserve compatibility with the auto-stamp-on-activation flow) and the global optimistic-lock
 * {@code version}.
 *
 * <p>Everything else stays server-managed and is structurally absent from this record so a caller
 * cannot smuggle in {@code id} / {@code owningSquadron} / {@code parent} / {@code owner} / {@code
 * managers} / collections via JSON (audit finding C-3). The path-variable {@code id} is the
 * authoritative target; the {@code version} field is the optimistic-lock check.
 *
 * <p>For section-scoped, multi-user-friendly updates prefer the dedicated patch endpoints ({@code
 * /core}, {@code /schedule}, {@code /flags}) which use their own per-section version counters and
 * avoid the all-three-counters bump that this DTO triggers in the service layer.
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
