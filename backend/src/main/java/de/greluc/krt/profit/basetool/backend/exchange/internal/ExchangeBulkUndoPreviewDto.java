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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import java.time.Instant;

/**
 * What a bulk undo would reach, shown before the admin confirms it (REQ-XCH-034).
 *
 * @param since the start of the span after clamping to the retention
 * @param members how many members have writes in scope
 * @param entries how many journal entries are in scope
 * @param clientActive whether the client is still active, so starting suspends it first
 */
public record ExchangeBulkUndoPreviewDto(
    Instant since, int members, long entries, boolean clientActive) {}
