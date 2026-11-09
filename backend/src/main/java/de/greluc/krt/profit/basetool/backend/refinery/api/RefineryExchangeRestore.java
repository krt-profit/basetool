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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A removed refinery order as the undo of a client's delete recreates it, under its former id
 * (REQ-XCH-056).
 *
 * @param id the order's former id
 * @param edit its location, status and goods with their former row ids, and every other part it
 *     held; the location and the status are required
 * @param gameDataVersion the game-data counter it comes back with
 * @param financesVersion the finances counter it comes back with
 * @param origin its creation, unit, mission, pickup and notice markers; the unit only while the
 *     owner is a direct member of it, the mission only while that mission exists
 */
public record RefineryExchangeRestore(
    @NotNull UUID id,
    @NotNull RefineryExchangeEdit edit,
    long gameDataVersion,
    long financesVersion,
    @NotNull RefineryExchangeOrigin origin) {}
