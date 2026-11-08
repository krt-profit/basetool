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
import org.jetbrains.annotations.Nullable;

/**
 * The bank switch of the exchange (REQ-BXCH-006).
 *
 * @param enabled whether the bank route family serves requests while the exchange is on
 * @param updatedAt when the bank switch last changed, or {@code null} before its first change
 * @param version the bank switch's own optimistic-lock version
 */
public record ExchangeBankSwitchDto(boolean enabled, @Nullable Instant updatedAt, long version) {}
