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

package de.greluc.krt.profit.basetool.frontend.materialexchange.model;

/**
 * Outbound mirror of the backend {@code MaterialRequestUpdateRequest}: the editable fields of a
 * Materialbörse request plus its optimistic-lock version.
 *
 * @param desiredAmount the new wanted quantity
 * @param minQuality the new minimum quality 0–1000, or {@code null} for no floor
 * @param remark the new description, or {@code null}
 * @param version the last seen optimistic-lock version
 */
public record MaterialRequestUpdateRequest(
    Double desiredAmount, Integer minQuality, String remark, Long version) {}
