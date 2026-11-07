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
 * One entry of {@code resourceGroups} in catalogue version 2, a commodity category (REQ-P4K-025).
 *
 * @param guid the Record GUID
 * @param className the Record name, e.g. {@code Metal}
 * @param recordPath the record path without {@code .xml}
 * @param name the localised name; {@code en} and {@code de} are {@code null} for a placeholder
 * @param parentGuid the GUID of the group that lists this one, {@code null} for a top-level group
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kResourceGroupDto(
    @Nullable String guid,
    @Nullable String className,
    @Nullable String recordPath,
    @Nullable P4kLocalisedText name,
    @Nullable String parentGuid)
    implements P4kRecord {}
