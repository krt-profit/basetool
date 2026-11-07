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
 * One entry of {@code commodities} in catalogue version 2, bound to the fields the import writes or
 * matches on; every other field is skipped.
 *
 * @param guid the Record GUID, the commodity's identity
 * @param className the Record name
 * @param recordPath the record path without {@code .xml}
 * @param name the localised name
 * @param description the localised description, a game text
 * @param typeGuid the GUID of its resource group, or {@code null}
 * @param subtypeGuid the GUID of its {@code ResourceType}, an n:1 join and not a key, or {@code
 *     null}
 * @param densityGramsPerCubicCentimetre the density in g/cm³, or {@code null}
 * @param refinedSubtypeGuid the {@code ResourceType} it refines into, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kCommodityDto(
    @Nullable String guid,
    @Nullable String className,
    @Nullable String recordPath,
    @Nullable P4kLocalisedText name,
    @Nullable P4kLocalisedText description,
    @Nullable String typeGuid,
    @Nullable String subtypeGuid,
    @Nullable Double densityGramsPerCubicCentimetre,
    @Nullable String refinedSubtypeGuid)
    implements P4kRecord {}
