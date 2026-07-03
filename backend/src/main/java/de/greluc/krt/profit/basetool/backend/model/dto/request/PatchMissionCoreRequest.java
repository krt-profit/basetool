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
 * Request DTO for a partial update of the master-data (core) section of a mission.
 *
 * <p>The {@code version} field is the dedicated {@code mission.core_version} section counter — not
 * the global {@code Mission.@Version}. This is what enables concurrent users to edit the schedule
 * or flags section in parallel without their saves invalidating this form (or vice versa).
 *
 * <p>{@code operationId} is part of the core section: changing the parent operation does not bump
 * the schedule or flags counters and only requires a fresh {@code version} value here.
 *
 * <p>{@code calendarLink} is rendered as an {@code &lt;a href&gt;} on the public landing page (see
 * {@code index.html}). The {@code @Pattern} forces an {@code https://} prefix so a mission manager
 * cannot persist a {@code javascript:fetch(document.cookie)} stored-XSS payload — Thymeleaf's
 * {@code th:href} only HTML-escapes the value, it does not scheme-filter. Audit finding H-1.
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
