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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import java.util.List;

/**
 * The open material demand in brief: how many buckets still have a gap and the first of them
 * (REQ-ORDERS-034, REQ-UI-034).
 *
 * @param openBuckets the number of buckets with an outstanding amount, all rows included
 * @param rows the first open buckets in the overview's order, at most the requested limit
 */
public record MaterialDemandSummaryDto(long openBuckets, List<MaterialDemandSummaryRowDto> rows) {}
