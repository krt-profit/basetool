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

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One finished-item line in an item-order payload: which game item to order, via which blueprint,
 * in what amount, with per-material quality choices.
 *
 * <p>Echo the existing {@code id} when editing so the line is updated in place and keeps its booked
 * amounts (REQ-ORDERS-032); a {@code null} id means a new line.
 *
 * @param id the existing line this payload updates, or {@code null} to add a new line
 * @param gameItemId the finished item to order
 * @param blueprintId the recipe chosen to produce it (must output {@code gameItemId})
 * @param amount whole-unit count to order (≥ 1)
 * @param materials per-material quality choices; omitted materials use the blueprint default
 * @param clientLineId transient client id of this line for provenance linking; may be {@code null}
 * @param parentClientLineId transient client id of the parent line; {@code null} for a top-level
 *     line
 */
public record CreateJobOrderItemLineDto(
    @Nullable UUID id,
    @NotNull UUID gameItemId,
    @NotNull UUID blueprintId,
    @NotNull @Min(1) Integer amount,
    @Size(max = 100) List<@Valid CreateJobOrderItemMaterialDto> materials,
    @Nullable Integer clientLineId,
    @Nullable Integer parentClientLineId) {}
