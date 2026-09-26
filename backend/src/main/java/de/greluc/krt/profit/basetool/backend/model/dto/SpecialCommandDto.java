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
import java.util.UUID;

/**
 * Wire shape for {@link de.greluc.krt.profit.basetool.backend.model.SpecialCommand}; mirrors {@link
 * SquadronDto} without the promotion toggle, which is always off for a Spezialkommando.
 *
 * @param id Spezialkommando identifier; {@code null} on create, required on update
 * @param name display name, case-insensitively unique across {@link
 *     de.greluc.krt.profit.basetool.backend.model.OrgUnitKind#SPECIAL_COMMAND} and {@link
 *     de.greluc.krt.profit.basetool.backend.model.OrgUnitKind#SQUADRON}; required, max 255 chars
 * @param shorthand short tag for chips and badges, unique like {@link #name}; required, max 255
 *     chars
 * @param description free-form text; nullable
 * @param active soft-delete flag; {@code null} on requests means no change
 * @param isProfitEligible whether the SK may process job orders; changed only through {@code
 *     /api/v1/special-commands/{id}/profit-eligible}
 * @param version optimistic-lock counter; required on update
 */
public record SpecialCommandDto(
    UUID id,
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 255) String shorthand,
    @Size(max = 65_535) String description,
    Boolean active,
    Boolean isProfitEligible,
    Long version) {}
