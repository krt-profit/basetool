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

import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import java.util.UUID;

/**
 * Lean org-unit option derived from a user's membership, for {@code <select>} pickers.
 *
 * @param orgUnitId the org unit's id, used as the option value
 * @param orgUnitName the org unit's name, used as the option label
 * @param orgUnitShorthand the org unit's shorthand, or {@code null} when none is set
 * @param kind the org unit's kind, used to group options into {@code <optgroup>} sections
 * @param isProfitEligible whether the org unit may be picked as the responsible unit of a Job Order
 */
public record OrgUnitMembershipOptionDto(
    UUID orgUnitId,
    String orgUnitName,
    String orgUnitShorthand,
    OrgUnitKind kind,
    Boolean isProfitEligible) {}
