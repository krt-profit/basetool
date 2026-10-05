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

import java.util.UUID;

/**
 * Outbound mirror of the backend {@code MaterialRequestCreateRequest}: posts a material
 * wanted-listing to the Materialbörse.
 *
 * @param materialId the wanted material
 * @param minQuality the minimum quality 0–1000, or {@code null} for no floor
 * @param requestedAmount the wanted quantity in the material's unit
 * @param remark the description, or {@code null}
 */
public record MaterialRequestCreateRequest(
    UUID materialId, Integer minQuality, Double requestedAmount, String remark) {}
