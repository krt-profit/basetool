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
import java.util.List;

/**
 * Frontend mirror of the backend {@code MyDonationStatsDto}; must match it field for field.
 *
 * @param donations the number of received donations
 * @param entries the received amount per material or item
 */
@DtoMirror
public record MyDonationStatsDto(long donations, List<SupplyStatsDto.EntryAmount> entries) {}
