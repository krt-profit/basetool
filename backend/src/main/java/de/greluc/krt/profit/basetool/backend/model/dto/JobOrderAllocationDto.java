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

import java.util.UUID;

/**
 * Outbound projection of one job-order slice of an inventory entry (Variante C, REQ-INV-027): the
 * earmarked order plus the {@code amount} of the entry's stock allocated to it. Rendered as an
 * orange chip with its amount; the sum of an entry's slices stays ≤ the entry amount.
 *
 * @param jobOrderId the earmarked job order's id
 * @param jobOrderDisplayId the order's human display id (the {@code #NNNN} shown on the chip)
 * @param amount the quantity of the entry's stock allocated to this order (SCU, 3-decimal)
 */
public record JobOrderAllocationDto(UUID jobOrderId, Integer jobOrderDisplayId, Double amount) {}
