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

import de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One Lager row earmarked to a job order, as the order's stock views show it (REQ-ORDERS-028).
 *
 * @param id the row
 * @param version the row's version, {@code 0} when it has none yet
 * @param ownerName the owner's display name, or the user name when none is set
 * @param ownerId the owner
 * @param locationName the storage location's name
 * @param locationId the storage location
 * @param materialName the material's name of a material row, otherwise {@code null}
 * @param quality the quality of a material row, otherwise {@code null}
 * @param gameItem the game item of an item row, otherwise {@code null}
 * @param amount the row's stock
 * @param allocatedAmount the order's slice of the row, or the whole stock when the row carries no
 *     slice of the order
 * @param delivered whether the order's slice is marked delivered
 */
public record LinkedStockRow(
    @NotNull UUID id,
    long version,
    @NotNull String ownerName,
    @NotNull UUID ownerId,
    @NotNull String locationName,
    @NotNull UUID locationId,
    @Nullable String materialName,
    @Nullable Integer quality,
    @Nullable InventoryGameItemReferenceDto gameItem,
    @NotNull Double amount,
    @NotNull Double allocatedAmount,
    boolean delivered) {}
