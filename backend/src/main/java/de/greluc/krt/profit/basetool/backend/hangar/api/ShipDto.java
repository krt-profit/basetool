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

package de.greluc.krt.profit.basetool.backend.hangar.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationDto;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipTypeDto;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.kernel.SquadronReferenceDto;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Data transfer record carrying Ship payload.
 *
 * @param id the ship's id
 * @param name the ship's own name, or {@code null}
 * @param shipType the ship's type
 * @param insurance {@code LTI} or a number of months
 * @param location the ship's location, or {@code null}
 * @param fitted whether the ship is fitted
 * @param owner the owning member, or {@code null} where it is not disclosed
 * @param owningSquadron the owning org unit, or {@code null} for none
 * @param version the row version, echoed on an edit of the ship's fields
 * @param owningOrgUnitVersion the unit counter, echoed on an org-unit change (ADR-0258)
 */
public record ShipDto(
    UUID id,
    String name,
    ShipTypeDto shipType,
    String insurance,
    @Nullable LocationDto location,
    boolean fitted,
    @Nullable UserDto owner,
    @Nullable SquadronReferenceDto owningSquadron,
    Long version,
    long owningOrgUnitVersion) {}
