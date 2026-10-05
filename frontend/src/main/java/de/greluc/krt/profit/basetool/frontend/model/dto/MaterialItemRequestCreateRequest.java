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

/**
 * Outbound mirror of the backend {@code MaterialItemRequestCreateRequest}: posts a craftable-item
 * wanted-listing to the Materialbörse.
 *
 * @param productKey the normalized blueprint product key
 * @param minQuality the minimum quality 0–1000, or {@code null} for no floor
 * @param quantity the wanted whole-piece quantity
 * @param remark the description, or {@code null}
 */
public record MaterialItemRequestCreateRequest(
    String productKey, Integer minQuality, Integer quantity, String remark) {}
