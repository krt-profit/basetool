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

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * An admin's change of a UEX location row's starmap link (REQ-P4K-035), relayed to the backend as a
 * compare-and-set on the link the admin saw.
 *
 * @param action {@code SET}, {@code CLEAR} or {@code RELEASE}
 * @param starmapObjectId the object to link for {@code SET}, else {@code null}
 * @param expectedStarmapObjectId the link the admin saw, or {@code null} when the row showed none
 * @param expectedManual the manual flag the admin saw
 */
@DtoMirror
public record StarmapLinkRequest(
    @NotNull @BackendEnumAsString String action,
    UUID starmapObjectId,
    UUID expectedStarmapObjectId,
    @NotNull Boolean expectedManual) {}
