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
 * One entry of {@code items} in catalogue version 2, bound to the fields the import writes or
 * decides on; every other field is skipped.
 *
 * @param guid the Record GUID
 * @param className the Record name
 * @param recordPath the record path without {@code .xml}
 * @param name the localised name
 * @param description the localised description, a game text
 * @param mass the mass in kilograms, or {@code null}
 * @param manufacturerGuid the GUID of the manufacturer, or {@code null}
 * @param playability {@code player}, {@code mission}, {@code special}, another value or {@code
 *     null}; read through {@link P4kClosedSet#parse} as a {@link P4kItemPlayability}
 * @param itemClass the raw {@code itemClass} ({@code Civilian}, {@code Military}, …) or {@code
 *     null}; its null share per run is a monitoring gauge (REQ-OBS-022)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kItemDto(
    @Nullable String guid,
    @Nullable String className,
    @Nullable String recordPath,
    @Nullable P4kLocalisedText name,
    @Nullable P4kLocalisedText description,
    @Nullable Double mass,
    @Nullable String manufacturerGuid,
    @Nullable String playability,
    @Nullable String itemClass)
    implements P4kRecord {}
