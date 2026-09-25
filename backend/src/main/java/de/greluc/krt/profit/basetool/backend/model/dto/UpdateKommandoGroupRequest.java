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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request body to rename and/or reorder a Kommandogruppe (REQ-ROLE-003); a stale {@code version}
 * yields 409.
 *
 * @param name the new display name; required, 1–120 chars.
 * @param sortIndex the new ascending display order within the squadron; zero or positive.
 * @param version the optimistic-lock version the client last read; required.
 */
public record UpdateKommandoGroupRequest(
    @NotBlank @Size(max = 120) String name, @PositiveOrZero int sortIndex, Long version) {}
