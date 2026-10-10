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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import java.time.Instant;
import org.jetbrains.annotations.NotNull;

/**
 * A removal in the change feed, naming who removed the entry (REQ-XCH-013).
 *
 * @param key the removed entry's opaque key
 * @param removedAt when it was removed
 * @param removedBy who removed it
 */
public record ExchangeTombstoneDto(
    @NotNull String key, @NotNull Instant removedAt, @NotNull ExchangeRemovedByDto removedBy) {}
