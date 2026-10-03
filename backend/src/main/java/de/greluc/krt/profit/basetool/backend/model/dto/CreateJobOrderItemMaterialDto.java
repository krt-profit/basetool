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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Per-material quality choice the requester makes for one ordered item line. The server re-derives
 * the required quantity from the blueprint (authoritative) and applies this {@code quality} to the
 * matching derived material; a material the client omits falls back to the blueprint-derived
 * default (the active tier with the highest floor the ingredient's {@code minQuality} reaches).
 *
 * @param materialId the material this choice applies to
 * @param quality the code of the requested quality tier, e.g. {@code GOOD} or {@code NONE}
 *     (REQ-ORDERS-036)
 */
public record CreateJobOrderItemMaterialDto(
    @NotNull UUID materialId, @NotBlank @Size(max = 32) String quality) {}
