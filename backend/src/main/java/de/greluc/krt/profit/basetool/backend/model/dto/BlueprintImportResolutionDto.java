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
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * The user's decision for one external name in a blueprint import apply request. A blank or {@code
 * null} {@link #productKey} skips the name; a manual choice is learned as an external alias.
 *
 * @param externalName the export {@code productName} this decision applies to
 * @param productKey normalized key of the chosen product, or blank / {@code null} to skip
 * @param acquiredAt optional acquisition time to stamp
 * @param note optional free-form note (max 2000 chars)
 */
public record BlueprintImportResolutionDto(
    @NotBlank String externalName,
    String productKey,
    Instant acquiredAt,
    @Size(max = 2000) String note) {}
