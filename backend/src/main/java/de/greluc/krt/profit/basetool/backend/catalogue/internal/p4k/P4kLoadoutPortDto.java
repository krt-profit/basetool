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
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * One port of a loadout (contract, "Loadouts", "Ports"), bound to the fields the import stores or
 * counts; {@code portTags}, {@code visibility}, {@code itemClassName} and {@code dockedVehicle} are
 * skipped.
 *
 * @param name the port name
 * @param source {@code loadout}, {@code override}, {@code port_default}, {@code unset}, {@code
 *     vehicle} or a value the game adds; read through {@link P4kClosedSet#parse} as a {@link
 *     P4kPortSource}
 * @param minSize the smallest item size the port takes, or {@code null}
 * @param maxSize the largest item size the port takes, or {@code null}
 * @param types the item types the port takes, in stored order
 * @param flags the port's flags without a leading {@code $}
 * @param requiredTags the tags an item must carry to fit the port
 * @param displayNameKey the localisation key of the port's display name, or {@code null}
 * @param itemGuid the GUID of the entity the port holds by default, or {@code null} for an empty
 *     port
 * @param unresolved the code of the error that kept the attachment from being resolved, or {@code
 *     null}
 * @param ports the nested ports
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kLoadoutPortDto(
    @Nullable String name,
    @Nullable String source,
    @Nullable Integer minSize,
    @Nullable Integer maxSize,
    @Nullable List<P4kPortTypeDto> types,
    @Nullable List<String> flags,
    @Nullable List<String> requiredTags,
    @Nullable String displayNameKey,
    @Nullable String itemGuid,
    @Nullable String unresolved,
    @Nullable List<P4kLoadoutPortDto> ports) {}
