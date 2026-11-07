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
 * One starmap object as the admin starmap page lists it (REQ-P4K-034, REQ-P4K-038). The names are
 * game texts and reach admins only.
 *
 * @param id the object
 * @param version the optimistic-lock version, echoed by a visibility override
 * @param guid the P4K GUID
 * @param className the Record name
 * @param type the starmap type
 * @param starSystem the star system folder, or {@code null}
 * @param parentId the parent object, or {@code null}
 * @param name the English P4K name, or {@code null}
 * @param nameDe the German P4K name, or {@code null}
 * @param hideInStarmap the Record's flag, or {@code null}
 * @param hasPosition whether the catalogue placed the object
 * @param visibilityOverride the admin's override, or {@code null} while the rule decides
 * @param visible whether the object is visible after rule and override
 * @param retired whether the sweep stamped the object or an admin hid it through a review case
 * @param storagePlaceId the storage place on the object, or {@code null}
 */
public record StarmapObjectAdminDto(
    @NotNull UUID id,
    long version,
    @NotNull UUID guid,
    @Nullable String className,
    @Nullable String type,
    @Nullable String starSystem,
    @Nullable UUID parentId,
    @Nullable String name,
    @Nullable String nameDe,
    @Nullable Boolean hideInStarmap,
    boolean hasPosition,
    @Nullable StarmapVisibilityOverride visibilityOverride,
    boolean visible,
    boolean retired,
    @Nullable UUID storagePlaceId) {}
