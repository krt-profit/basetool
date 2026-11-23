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

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The choices a client offers before a member creates an aUEC order (REQ-OXCH-004): both lists are
 * empty when the member may create no order, and the read names no member.
 *
 * @param requestingUnits the member's writer units: the active units they directly belong to and
 *     are a Logistician of
 * @param responsibleUnits the eligible Profit units
 * @param suggestedResponsible the canonical Profit area when it is among the responsible units,
 *     else {@code null}
 * @param writesEnabled whether the job-order writes switch is on, so a create can be served
 */
public record ExchangeJobOrderCreateOptionsDto(
    @NotNull @Unmodifiable List<ExchangeOrgUnitDto> requestingUnits,
    @NotNull @Unmodifiable List<ExchangeOrgUnitDto> responsibleUnits,
    @Nullable UUID suggestedResponsible,
    boolean writesEnabled) {}
