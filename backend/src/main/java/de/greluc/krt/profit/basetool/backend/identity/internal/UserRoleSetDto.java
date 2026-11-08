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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A user's assignable roles as the caller may see them, with the role-set counter to echo on the
 * next change (REQ-SEC-088, ADR-0355).
 *
 * @param userId the user
 * @param roles the held roles among those the caller may assign, in {@link AssignableRole} order
 * @param assignable the roles the caller may assign and withdraw, in {@link AssignableRole} order
 * @param version the role-set counter
 */
public record UserRoleSetDto(
    @NotNull UUID userId,
    @NotNull List<AssignableRole> roles,
    @NotNull List<AssignableRole> assignable,
    long version) {}
