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

import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code MissionUnitDto}: one unit assigned to a mission.
 *
 * <p>When {@code responsibleUser} is {@code null} the view falls back to the assigned ship's owner.
 * {@code version} is echoed back by the edit form for optimistic locking.
 */
public record MissionUnitDto(
    UUID id,
    String name,
    ShipTypeDto shipType,
    ShipDto ship,
    Double frequency,
    Boolean highValueUnit,
    UserReferenceDto responsibleUser,
    String note,
    Long version,
    List<MissionCrewDto> crew) {}
