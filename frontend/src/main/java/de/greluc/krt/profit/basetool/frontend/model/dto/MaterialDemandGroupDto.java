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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.List;

/**
 * Frontend mirror of the backend {@code MaterialDemandGroupDto}: the material demand of one
 * responsible (processing) org unit in the cross-order overview (REQ-ORDERS-034). One group renders
 * as one section of the page, headed by the unit's badge.
 *
 * @param orgUnit the responsible squadron or Spezialkommando; {@code null} for the fallback group
 *     collecting orders whose responsible unit could not be resolved, which the template labels
 *     explicitly rather than hiding
 * @param materials the unit's aggregated material buckets, pre-sorted by the backend
 */
public record MaterialDemandGroupDto(
    SquadronReferenceDto orgUnit, List<MaterialDemandRowDto> materials) {}
