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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jetbrains.annotations.Nullable;

/**
 * One entry of {@code ships} in catalogue version 2, bound to the fields the import writes or
 * decides on; every other field is skipped.
 *
 * @param guid the Record GUID
 * @param className the Record name
 * @param recordPath the record path without {@code .xml}
 * @param name the localised name
 * @param description the localised description, a game text
 * @param manufacturerGuid the GUID of the manufacturer, or {@code null}
 * @param playability {@code player}, {@code ai}, {@code mission}, {@code special}, another value or
 *     {@code null}; read through {@link P4kClosedSet#parse} as a {@link P4kShipPlayability}
 * @param variantOf the GUID of the base of the ship's variant group, {@code null} for a base and an
 *     ungrouped ship
 * @param role the localised role
 * @param career the localised career
 * @param crewSize the crew size, or {@code null}
 * @param mass the hull mass in kilograms, or {@code null}
 * @param hullHealth the hull hit points, or {@code null}
 * @param size the item size of the attach definition, or {@code null}
 * @param vehicleKind {@code spaceship}, {@code groundVehicle}, another value or {@code null}; read
 *     as a {@link P4kVehicleKind}
 * @param cargoScu the cargo capacity in SCU, a lower bound while {@code cargoGridsWithIssue} is
 *     above 0, or {@code null}
 * @param cargoGridsWithIssue the cargo grids without a readable capacity, or {@code null}
 * @param insurance the insurance facts, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kShipDto(
    @Nullable String guid,
    @Nullable String className,
    @Nullable String recordPath,
    @Nullable P4kLocalisedText name,
    @Nullable P4kLocalisedText description,
    @Nullable String manufacturerGuid,
    @Nullable String playability,
    @Nullable String variantOf,
    @Nullable P4kLocalisedText role,
    @Nullable P4kLocalisedText career,
    @Nullable Integer crewSize,
    @Nullable Double mass,
    @Nullable Double hullHealth,
    @Nullable Integer size,
    @Nullable String vehicleKind,
    @Nullable Double cargoScu,
    @Nullable Integer cargoGridsWithIssue,
    @Nullable P4kShipInsurance insurance)
    implements P4kRecord {}
