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

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code MissionSaleAttributionDto} (per the {@code
 * feedback_backend_frontend_dto_mirror} memory): one per-mission income attribution of a {@code
 * SELL} book-out (Variante C, REQ-INV-027). The seller credits {@code amount} of the sale proceeds
 * to mission {@code missionId}; the SELL carries a list of these and the uncredited remainder is
 * the seller's own proceeds.
 *
 * @param missionId the earmarked mission to credit; never {@code null}.
 * @param amount the share of the sale proceeds to book to the mission; strictly positive.
 */
public record MissionSaleAttributionDto(
    @NotNull UUID missionId, @NotNull @Positive BigDecimal amount) {}
