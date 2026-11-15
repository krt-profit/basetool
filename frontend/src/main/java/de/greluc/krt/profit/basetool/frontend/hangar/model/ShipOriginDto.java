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

package de.greluc.krt.profit.basetool.frontend.hangar.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/**
 * Frontend mirror of the backend {@code ShipOriginDto}: a ship's origin as the owner and admins see
 * it (REQ-HANGAR-017).
 *
 * @param value {@code PLEDGE}, {@code IN_GAME} or {@code UNKNOWN}; {@code PLEDGE} whenever locked
 * @param locked whether an import fixed the origin, so the edit dialog cannot change it
 */
@DtoMirror
public record ShipOriginDto(
    @BackendEnumAsString("hangar.ship.origin") String value, boolean locked) {}
