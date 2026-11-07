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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One UEX location row and its starmap link as the admin starmap page lists it (REQ-P4K-035).
 *
 * @param kind the UEX table
 * @param id the row
 * @param name the UEX name
 * @param starSystem the star system's name, or {@code null}
 * @param starmapObjectId the linked object, or {@code null}
 * @param starmapObjectGuid that object's P4K GUID, or {@code null}
 * @param starmapObjectClassName that object's Record name, or {@code null}
 * @param manual whether an admin set or cleared the link, so no APPLY changes it
 */
public record StarmapPlaceLinkDto(
    @NotNull StarmapPlaceKind kind,
    @NotNull UUID id,
    @Nullable String name,
    @Nullable String starSystem,
    @Nullable UUID starmapObjectId,
    @Nullable UUID starmapObjectGuid,
    @Nullable String starmapObjectClassName,
    boolean manual) {}
