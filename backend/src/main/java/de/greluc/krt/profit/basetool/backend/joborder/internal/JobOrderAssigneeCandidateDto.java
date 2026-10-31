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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One hit of the assignee picker of a job order (REQ-ORDERS-047): the handle, whether the account
 * is a direct member of the order's processing unit, and otherwise the unit that marks it.
 *
 * @param id the account's user id
 * @param effectiveName the display name, or the username when none is set
 * @param member whether the account is a direct member of the order's processing unit
 * @param unitShorthand the shorthand of a non-member's primary unit, {@code null} for a member or
 *     an account without a unit
 * @param unitName the name of a non-member's primary unit, {@code null} like {@code unitShorthand}
 */
public record JobOrderAssigneeCandidateDto(
    UUID id,
    String effectiveName,
    boolean member,
    @Nullable String unitShorthand,
    @Nullable String unitName) {}
