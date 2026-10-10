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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.model.FinanceType;
import de.greluc.krt.profit.basetool.backend.validation.WholeNumber;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Data transfer record carrying Mission Finance Entry Update payload. Caps mirror the create
 * payload (audit finding C-2) so the post-create edit path cannot smuggle in payloads that would
 * have been rejected at create time — including the {@link WholeNumber} whole-aUEC constraint
 * (REQ-MISSION-001), so an edit cannot reintroduce a fractional amount.
 */
public record MissionFinanceEntryUpdateDto(
    @Size(max = 2000) String note,
    @NotNull FinanceType type,
    @NotNull @DecimalMin("0.0") @DecimalMax("1000000000.0") @WholeNumber BigDecimal amount,
    @NotNull Long version) {}
