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

import org.jetbrains.annotations.NotNull;

/**
 * An amount per earmark dimension of an org-stock lot or entry, in the lot's unit: the earmarked
 * part of a lot (REQ-XCH-042) or the free part of an entry (REQ-XCH-043).
 *
 * @param jobOrders the amount for the job-order dimension
 * @param missions the amount for the mission dimension
 */
public record ExchangeEarmarkedDto(
    @NotNull ExchangeQuantityDto jobOrders, @NotNull ExchangeQuantityDto missions) {}
