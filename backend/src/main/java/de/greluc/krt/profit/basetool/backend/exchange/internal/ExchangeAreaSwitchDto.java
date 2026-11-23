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
 * One area switch of the exchange beside the global switch: the bank (REQ-BXCH-006), the job-order
 * family or its writes (REQ-OXCH-009).
 *
 * @param enabled whether the area serves requests while the exchange is on
 * @param updatedAt when the switch last changed, or {@code null} before its first change
 * @param version the switch's own optimistic-lock version
 */
public record ExchangeAreaSwitchDto(boolean enabled, @Nullable Instant updatedAt, long version) {}
