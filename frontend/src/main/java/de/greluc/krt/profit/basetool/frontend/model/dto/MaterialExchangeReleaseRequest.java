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
 * Outbound mirror of the backend {@code MaterialExchangeReleaseRequest}: releases one of the
 * caller's Lager rows to the Materialbörse.
 *
 * @param inventoryItemId the Lager row to offer
 * @param offeredAmount the offered quantity in the material's unit
 * @param remark the trade remark, or {@code null}
 */
public record MaterialExchangeReleaseRequest(
    UUID inventoryItemId, Double offeredAmount, String remark) {}
