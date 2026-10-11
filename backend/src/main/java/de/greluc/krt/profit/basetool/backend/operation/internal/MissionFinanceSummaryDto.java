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

package de.greluc.krt.profit.basetool.backend.operation.internal;

import de.greluc.krt.profit.basetool.backend.model.dto.MissionFinanceEntryDto;
import de.greluc.krt.profit.basetool.backend.refinery.api.RefineryOrderDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One mission's line of an operation's finance view: its entries and its linked refinery orders.
 */
public record MissionFinanceSummaryDto(
    UUID missionId,
    String missionName,
    BigDecimal totalSum,
    List<MissionFinanceEntryDto> entries,
    List<RefineryOrderDto> refineryOrders) {}
