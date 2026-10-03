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

import de.greluc.krt.profit.basetool.backend.validation.QualityValue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create or update payload of a quality tier (REQ-ORDERS-036). On update the floor must stay
 * unchanged once the tier is referenced, and the base tier cannot be deactivated.
 *
 * @param code the upper-case code: a letter, then letters, digits or underscores, at most 32
 * @param minQuality the floor, 0 to 1000, unique across the catalogue
 * @param labelDe the German label, at most 64 characters
 * @param labelEn the English label, at most 64 characters
 * @param sortOrder the picker position, ascending
 * @param active whether the tier is offered for new requirements
 * @param version the version read; required on update, ignored on create
 */
public record QualityTierWriteDto(
    @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,31}$") String code,
    @NotNull @QualityValue Integer minQuality,
    @NotBlank @Size(max = 64) String labelDe,
    @NotBlank @Size(max = 64) String labelEn,
    @NotNull Integer sortOrder,
    @NotNull Boolean active,
    Long version) {}
