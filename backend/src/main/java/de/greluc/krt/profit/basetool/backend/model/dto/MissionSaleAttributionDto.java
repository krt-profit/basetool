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

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One per-mission income attribution of a {@code SELL} book-out (Variante C, REQ-INV-027): the
 * seller credits {@code amount} of the sale's total proceeds to mission {@code missionId} as
 * squadron {@code INCOME}.
 *
 * <p>A SELL carries a list of these. Only missions the sold row earmarks (a mission slice) and that
 * the seller participates in are creditable; the sum of a request's attributions must stay within
 * the sale's total {@code sellAmount}, and any uncredited remainder is the seller's own (personal)
 * proceeds. An empty list is a fully-personal sale that credits no mission — allowed even for
 * mission-earmarked stock.
 *
 * @param missionId the earmarked mission to credit; never {@code null}.
 * @param amount the share of the sale proceeds to book to the mission as INCOME; strictly positive
 *     (a mission the seller does not credit is simply omitted from the list rather than sent as
 *     zero).
 */
public record MissionSaleAttributionDto(
    @NotNull UUID missionId, @NotNull @Positive BigDecimal amount) {}
