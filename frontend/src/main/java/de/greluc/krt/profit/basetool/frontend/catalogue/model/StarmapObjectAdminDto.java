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
 * Frontend mirror of the backend {@code StarmapObjectAdminDto}: one P4K starmap object with its
 * visibility (REQ-P4K-034, REQ-P4K-038).
 *
 * @param id the object
 * @param version the optimistic-lock version a visibility override echoes
 * @param guid the P4K GUID
 * @param className the P4K class name, or {@code null}
 * @param type the P4K type, or {@code null}
 * @param starSystem the star system folder, or {@code null}
 * @param parentId the parent object, or {@code null} for a root
 * @param name the English P4K name, or {@code null}; a game text
 * @param nameDe the German P4K name, or {@code null}; a game text
 * @param hideInStarmap the P4K flag, or {@code null} when the catalogue does not state it
 * @param hasPosition whether the catalogue gives the object a position
 * @param visibilityOverride {@code VISIBLE}, {@code HIDDEN}, or {@code null} when the rule decides
 * @param visible whether the object is visible after rule and override
 * @param retired whether the last import no longer carried the object
 * @param storagePlaceId the storage place created on the object, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@DtoMirror
public record StarmapObjectAdminDto(
    UUID id,
    long version,
    UUID guid,
    String className,
    String type,
    String starSystem,
    UUID parentId,
    String name,
    String nameDe,
    Boolean hideInStarmap,
    boolean hasPosition,
    @BackendEnumAsString String visibilityOverride,
    boolean visible,
    boolean retired,
    UUID storagePlaceId) {}
