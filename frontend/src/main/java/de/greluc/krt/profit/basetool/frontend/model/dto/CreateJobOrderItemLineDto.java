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
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code CreateJobOrderItemLineDto}: one ordered finished-item line
 * in the item-order create payload.
 *
 * @param id the existing line this payload updates, or {@code null} for a new line; echoing it is
 *     what lets the backend edit the line in place instead of recreating it, preserving its booked
 *     production (REQ-ORDERS-032)
 * @param gameItemId the finished item to order
 * @param blueprintId the chosen recipe (must output {@code gameItemId})
 * @param amount whole-unit count (≥ 1)
 * @param materials per-material quality choices
 * @param clientLineId transient client id for provenance linking
 * @param parentClientLineId transient client id of the line this was adopted from
 */
public record CreateJobOrderItemLineDto(
    UUID id,
    UUID gameItemId,
    UUID blueprintId,
    Integer amount,
    List<CreateJobOrderItemMaterialDto> materials,
    Integer clientLineId,
    Integer parentClientLineId) {}
