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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A member's target change of an aUEC order through a client (REQ-OXCH-007).
 *
 * @param target the new target in whole aUEC, in the create's range
 * @param targetVersion the order's target version as the client last read it
 */
public record ExchangeJobOrderTargetChangeDto(
    @NotNull @Min(1) @Max(ExchangeJobOrderCreateDto.TARGET_MAX) Long target,
    @NotNull @Min(0) Long targetVersion) {}
