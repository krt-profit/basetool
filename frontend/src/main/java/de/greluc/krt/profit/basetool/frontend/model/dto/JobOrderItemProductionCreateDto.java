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

/**
 * Frontend mirror of the backend {@code JobOrderItemProductionCreateDto}: the production booking
 * the "Herstellung" modal posts, relayed verbatim to the backend by {@code
 * JobOrderWriteController.bookProductionAjax}.
 *
 * @param amount the whole units manufactured in this booking
 * @param version the ordered item line's optimistic-lock version
 * @param consumption the per-inventory-entry material draws
 */
public record JobOrderItemProductionCreateDto(
    Integer amount, Long version, List<JobOrderItemProductionConsumptionDto> consumption) {}
