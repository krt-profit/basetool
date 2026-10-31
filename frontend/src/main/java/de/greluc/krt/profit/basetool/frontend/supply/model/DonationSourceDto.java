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

package de.greluc.krt.profit.basetool.frontend.supply.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code DonationSourceDto}; must match it field for field.
 *
 * @param id the Lager row
 * @param locationName where the stock lies
 * @param quality the row's quality, or {@code null} for a game item
 * @param freeAmount what is not yet pledged from the row
 * @param quantityType {@code SCU}, {@code PIECE} or {@code ITEM}
 */
@DtoMirror
public record DonationSourceDto(
    UUID id, String locationName, Integer quality, double freeAmount, String quantityType) {}
