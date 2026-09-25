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
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Request DTO for a partial update of a mission's core (master-data) section, including its parent
 * operation.
 *
 * <p>{@code version} is the {@code mission.core_version} section counter, so schedule and flags
 * edits do not conflict with it. {@code calendarLink} must start with {@code https://} to prevent
 * stored XSS in the rendered link.
 */
public record PatchMissionCoreRequest(
    @NotBlank @Size(max = 255) String name,
    @Nullable @Size(max = 20000) String description,
    @Nullable
        @Size(max = 2048)
        @Pattern(regexp = DtoConstraints.HTTPS_URL_REGEX, message = "must start with https://")
        String calendarLink,
    @Nullable @Size(max = 64) String status,
    @Nullable UUID operationId,
    @NotNull Long version,
    @Nullable @Size(max = 200) String meetingPoint) {}
