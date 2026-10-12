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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Request to set or remove the „gestohlen" marker on a selection of the caller's own Lager rows,
 * whole rows only (REQ-INV-053). Like the bulk rebooking it carries no versions: the rows are
 * locked pessimistically.
 *
 * @param itemIds the selected rows; every one must be the caller's own
 * @param stolen the requested marker
 */
public record BulkStolenMarkRequest(
    @NotNull @NotEmpty List<@NotNull UUID> itemIds, @NotNull Boolean stolen) {}
