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

package de.greluc.krt.profit.basetool.frontend.exchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import org.jetbrains.annotations.NotNull;

/**
 * What a staged change set does, or did, as the backend reports it (REQ-XCH-021).
 *
 * @param clientName the client's registered display name
 * @param resource {@code blueprints}, {@code stock} or {@code ships}
 * @param dryRun whether this is the preview, which wrote nothing
 * @param applied the ops that change, or changed, something
 * @param unchanged the ops that find their state already
 * @param notApplied the ops refused or not matched
 */
@DtoMirror
public record ConnectedAppMassChangeResultDto(
    @NotNull String clientName,
    @NotNull String resource,
    boolean dryRun,
    int applied,
    int unchanged,
    int notApplied) {}
