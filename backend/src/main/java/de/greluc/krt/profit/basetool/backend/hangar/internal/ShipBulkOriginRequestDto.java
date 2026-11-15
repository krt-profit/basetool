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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.hangar.api.ShipOrigin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Write payload of a bulk origin change of one member's ships (REQ-HANGAR-018).
 *
 * @param shipIds the selected ships, 1 to {@link ShipBulkOrgUnitRequestDto#MAX_SHIPS}
 * @param origin the new origin, {@code PLEDGE} or {@code IN_GAME}
 */
public record ShipBulkOriginRequestDto(
    @NotNull @NotEmpty @Size(max = ShipBulkOrgUnitRequestDto.MAX_SHIPS) List<@NotNull UUID> shipIds,
    @NotNull ShipOrigin origin) {}
