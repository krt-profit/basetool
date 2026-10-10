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

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;

/**
 * What an admin's bulk undo of one client covers (REQ-XCH-034).
 *
 * @param since the start of the span; clamped to the journal's retention
 * @param installationId the one installation to undo, or {@code null} for all
 * @param resource {@code BLUEPRINT}, {@code STOCK} or {@code SHIP} to undo only that, or {@code
 *     null} for all
 */
public record ExchangeBulkUndoRequest(
    @NotNull Instant since,
    UUID installationId,
    @Pattern(regexp = "^(BLUEPRINT|STOCK|SHIP)$") String resource) {}
