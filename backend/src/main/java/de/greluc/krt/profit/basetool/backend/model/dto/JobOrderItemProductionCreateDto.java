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
import java.util.List;

/**
 * Create payload for a production booking ("Herstellung", REQ-ORDERS-025) against one ordered item
 * line: how many whole units were manufactured and, per required material, exactly which linked
 * inventory entries the material was drawn from. The {@code consumption} plan must cover the demand
 * ({@code perUnit × amount}) of every required material exactly.
 *
 * @param amount the whole units manufactured in this booking (≥ 1, ≤ the line's
 *     remaining-to-manufacture)
 * @param version the ordered item line's optimistic-lock version (echoed for the 409 guard)
 * @param consumption the per-inventory-entry material draws that must exactly cover the demand;
 *     empty is allowed only for an item line with no derivable material requirements (nothing to
 *     consume)
 */
public record JobOrderItemProductionCreateDto(
    @NotNull @Min(1) Integer amount,
    @NotNull Long version,
    @NotNull List<@Valid JobOrderItemProductionConsumptionDto> consumption) {}
