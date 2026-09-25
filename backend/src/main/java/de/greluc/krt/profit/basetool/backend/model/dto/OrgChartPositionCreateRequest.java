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

import de.greluc.krt.profit.basetool.backend.model.OrgChartPositionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Payload of {@code POST /api/v1/org-chart/positions}, which creates a functional-rank position or
 * a Kommando. {@code OrgChartService} validates the field combination against the scope,
 * cardinality and parent rules.
 *
 * @param positionType the functional rank to assign; required
 * @param orgUnitId the owning Staffel/SK; {@code null} for area-leadership ranks
 * @param userId the account holding the position; exactly one of {@code userId} and {@code
 *     displayName} is required, except for {@code COMMAND_LEAD}, where both may be omitted
 * @param parentId the parent position of a deputy or an Ensign; {@code null} for root ranks
 * @param name the Kommando name, honoured only for {@code COMMAND_LEAD}; blank means unnamed
 * @param sortIndex optional order within the sibling group; defaults to {@code 0}
 * @param displayName free-text holder name for a member without an account; mutually exclusive with
 *     {@code userId}
 */
public record OrgChartPositionCreateRequest(
    @NotNull OrgChartPositionType positionType,
    UUID orgUnitId,
    UUID userId,
    UUID parentId,
    @Size(max = 120) String name,
    Integer sortIndex,
    @Size(max = 120) String displayName) {}
