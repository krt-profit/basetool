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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * One Lager row a consumption drew from, as the caller's audit trail records it.
 *
 * @param rowId the row
 * @param label the row's audit label
 * @param name the material's or game item's name
 * @param amount the amount taken
 * @param remaining what the row still holds, {@code 0} when depleted
 * @param depleted whether the row was deleted
 */
public record StockConsumption(
    @NotNull UUID rowId,
    @NotNull String label,
    @NotNull String name,
    double amount,
    double remaining,
    boolean depleted) {}
