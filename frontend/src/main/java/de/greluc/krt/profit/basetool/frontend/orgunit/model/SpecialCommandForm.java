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

package de.greluc.krt.profit.basetool.frontend.orgunit.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Form-binding object for Spezialkommando input on the {@code /admin/special-commands} create /
 * update modal. Mirrors {@link SquadronForm} field-for-field — the SK admin surface has no
 * Spezialkommando-specific fields beyond what the parent {@code OrgUnit} carries.
 *
 * @param name display name; required, max 255 chars.
 * @param shorthand short tag used on chips / badges; required, max 50 chars.
 * @param description free-form text; max 1000 chars (matches the backend column).
 * @param version optimistic-lock counter; required on update, ignored on create.
 */
public record SpecialCommandForm(
    @NotBlank(message = "{validation.name.required}") @Size(max = 255) String name,
    @NotBlank(message = "{validation.shorthand.required}") @Size(max = 50) String shorthand,
    @Size(max = 1000) String description,
    Long version) {}
