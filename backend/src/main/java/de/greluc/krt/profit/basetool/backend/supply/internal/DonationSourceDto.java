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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One of the caller's own Lager rows a pledge can be made from (REQ-SUP-025).
 *
 * @param id the Lager row
 * @param locationName where the stock lies
 * @param quality the row's quality, or {@code null} for a game item
 * @param freeAmount what is not yet pledged from the row
 * @param quantityType {@code SCU}, {@code PIECE} or {@code ITEM}
 */
public record DonationSourceDto(
    UUID id,
    String locationName,
    @Nullable Integer quality,
    double freeAmount,
    String quantityType) {}
