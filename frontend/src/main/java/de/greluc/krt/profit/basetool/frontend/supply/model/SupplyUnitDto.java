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

package de.greluc.krt.profit.basetool.frontend.supply.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code SupplyUnitDto}; must match it field for field.
 *
 * @param orgUnitId the unit
 * @param name the unit's name
 * @param shorthand the unit's shorthand
 * @param kind the unit's kind
 * @param mayRaiseNeeds whether the unit raises needs
 * @param acceptsDonations whether the unit accepts donations
 * @param canManage whether the caller leads the unit or is one of its Logisticians
 * @param version the setting's version, or {@code null} while the unit has no setting
 */
@DtoMirror
public record SupplyUnitDto(
    UUID orgUnitId,
    String name,
    String shorthand,
    @BackendEnumAsString String kind,
    boolean mayRaiseNeeds,
    boolean acceptsDonations,
    boolean canManage,
    Long version) {}
