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

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The user's membership state after a membership-delta write, so the form can re-render without a
 * follow-up GET.
 *
 * @param memberships the user's complete Staffel and SK memberships, Staffeln first, then SKs
 *     alphabetically; never {@code null}, possibly empty.
 */
public record MembershipDeltaResponse(@NotNull List<OrgUnitMembershipDto> memberships) {}
