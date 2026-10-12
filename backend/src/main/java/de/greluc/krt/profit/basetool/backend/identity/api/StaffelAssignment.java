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

package de.greluc.krt.profit.basetool.backend.identity.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One desired Staffel membership of a user, as the identity module hands it to {@link
 * MembershipOperations#reconcileStaffelMemberships}.
 *
 * @param squadronId the target Squadron id
 * @param isLogistician the desired Logistician flag for this Squadron; {@code null} means {@code
 *     false}
 * @param isMissionManager the desired Mission Manager flag; {@code null} means {@code false}
 */
public record StaffelAssignment(
    @NotNull UUID squadronId,
    @Nullable Boolean isLogistician,
    @Nullable Boolean isMissionManager) {}
