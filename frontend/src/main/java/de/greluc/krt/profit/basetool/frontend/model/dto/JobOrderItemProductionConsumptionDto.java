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
 * Frontend mirror of the backend {@code JobOrderItemProductionConsumptionDto}: one inventory-entry
 * draw of a production booking.
 *
 * @param inventoryItemId the linked inventory entry drawn from
 * @param materialId the material the entry holds
 * @param amount the SCU/PIECE consumed from this entry
 * @param version the inventory entry's optimistic-lock version
 */
public record JobOrderItemProductionConsumptionDto(
    UUID inventoryItemId, UUID materialId, Double amount, Long version) {}
