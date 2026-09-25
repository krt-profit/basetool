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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.UUID;

/**
 * Frontend mirror of the backend {@code OrgUnitMembershipOptionDto}: one option of the
 * owning-org-unit picker, as returned by {@code GET /api/v1/users/{id}/memberships}.
 *
 * @param orgUnitId Identifier of the org unit (used as the {@code <option value="...">}).
 * @param orgUnitName Visible name (used as the option label).
 * @param orgUnitShorthand Abbreviated badge text; may be {@code null}.
 * @param kind Discriminator string ({@code SQUADRON} / {@code SPECIAL_COMMAND} / {@code BEREICH} /
 *     {@code ORGANISATIONSLEITUNG}); the membership picker only ever sees the first two.
 * @param isProfitEligible Whether the org unit may be the responsible unit of a Job Order; {@code
 *     null} counts as not eligible.
 */
public record OrgUnitMembershipOptionDto(
    UUID orgUnitId,
    String orgUnitName,
    String orgUnitShorthand,
    @BackendEnumAsString String kind,
    Boolean isProfitEligible) {}
