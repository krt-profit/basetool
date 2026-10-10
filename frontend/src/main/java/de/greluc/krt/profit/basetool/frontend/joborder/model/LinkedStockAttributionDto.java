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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import java.util.UUID;

/**
 * How much of one linked stock row counts toward one quality bucket of an order (REQ-ORDERS-037).
 *
 * @param inventoryItemId the linked stock row
 * @param qualityTierId the bucket's quality tier, or {@code null} for the part no bucket accepts
 * @param amount the attributed amount, in the material's unit
 */
public record LinkedStockAttributionDto(UUID inventoryItemId, UUID qualityTierId, double amount) {}
