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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * One material line of a {@code MATERIAL} job order.
 *
 * @param id material-line primary key
 * @param material the required material, with its {@code quantityType}
 * @param minQuality the minimum acceptable quality (650) or {@code null} for none
 * @param amount the required amount in the material's own unit
 * @param currentStock the summed linked-inventory stock for this line
 * @param claims per-squadron claims on this bucket; populated only for public SK orders
 * @param openAmount {@code required − Σ claims}; {@code null} for non-SK orders
 * @param version optimistic-lock version
 */
public record JobOrderMaterialDto(
    UUID id,
    MaterialDto material,
    Integer minQuality,
    Double amount,
    Double currentStock,
    List<ClaimDto> claims,
    Double openAmount,
    Long version) {}
