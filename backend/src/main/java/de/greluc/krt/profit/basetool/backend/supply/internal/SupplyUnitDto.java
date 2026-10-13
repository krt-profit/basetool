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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * An org unit with what it does in the supply module (REQ-SUP-002).
 *
 * @param orgUnitId the unit
 * @param name the unit's name
 * @param shorthand the unit's shorthand
 * @param kind the unit's kind
 * @param mayRaiseNeeds whether the unit raises needs
 * @param acceptsDonations whether the unit accepts donations
 * @param canManage whether the caller leads the unit or is one of its Logisticians
 * @param version the setting's version, {@code null} while the unit has no setting row
 */
public record SupplyUnitDto(
    UUID orgUnitId,
    String name,
    String shorthand,
    OrgUnitKind kind,
    boolean mayRaiseNeeds,
    boolean acceptsDonations,
    boolean canManage,
    @Nullable Long version) {}
