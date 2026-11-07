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

package de.greluc.krt.profit.basetool.frontend.catalogue.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code StarmapPlaceLinkDto}: one UEX location row with its link to
 * the starmap (REQ-P4K-035).
 *
 * @param kind the UEX table, e.g. {@code CITY}
 * @param id the row
 * @param name the UEX name, or {@code null}
 * @param starSystem the row's star system, or {@code null}
 * @param starmapObjectId the linked starmap object, or {@code null} when unlinked
 * @param starmapObjectGuid the linked object's P4K GUID, or {@code null}
 * @param starmapObjectClassName the linked object's P4K class name, or {@code null}
 * @param manual whether an admin set the link by hand, so the import keeps it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@DtoMirror
public record StarmapPlaceLinkDto(
    @BackendEnumAsString String kind,
    UUID id,
    String name,
    String starSystem,
    UUID starmapObjectId,
    UUID starmapObjectGuid,
    String starmapObjectClassName,
    boolean manual) {}
