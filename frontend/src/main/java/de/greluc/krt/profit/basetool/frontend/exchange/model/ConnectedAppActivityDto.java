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
import java.time.Instant;

/**
 * Mirror of one recent write of a connected client to the member's data (REQ-XCH-032).
 *
 * @param recordedAt when it was written
 * @param resource {@code BLUEPRINT}, {@code STOCK} or {@code SHIP}
 * @param action the journal action, such as {@code BLUEPRINT_ADD} or {@code SHIP_REMOVE}
 * @param label the entry's name, or {@code null} when it is no longer known
 * @param undone whether the member has undone it
 */
@DtoMirror
public record ConnectedAppActivityDto(
    Instant recordedAt, String resource, String action, String label, boolean undone) {}
