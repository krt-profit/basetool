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

package de.greluc.krt.profit.basetool.backend.model.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Inbound payload for adding a mission-specific radio frequency (REQ-MISSION-014).
 *
 * @param name the free-text channel label (required, ≤100 chars).
 * @param value the frequency value (required, 0 – 999.99 with at most two decimals).
 */
public record AddCustomFrequencyRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull
        @DecimalMin(value = "0", inclusive = true)
        @DecimalMax(value = "999.99")
        @Digits(integer = 3, fraction = 2)
        BigDecimal value) {}
