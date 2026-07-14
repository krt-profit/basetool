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
import java.util.UUID;

/**
 * One line of a book-out / transfer "deduct from" plan (Variante C, REQ-INV-027): it takes {@code
 * amount} of the deducted quantity out of the entry's earmark to {@code targetId} (a job order or a
 * mission, depending on which dimension list carries it). The two dimensions of an entry —
 * job-order and mission splits — are independent, so a single deducted quantity is sourced
 * separately in each: {@link InventoryItemBookOutDto#jobOrderReductions()} spends it against
 * job-order slices and {@link InventoryItemBookOutDto#missionReductions()} against mission slices.
 * Whatever a dimension's reductions leave uncovered is taken from that dimension's not-yet-assigned
 * rest.
 *
 * @param targetId the job order or mission whose slice to shrink; never {@code null}
 * @param amount the SCU (or whole pieces) to subtract from that slice; strictly positive
 */
public record AllocationReductionDto(@NotNull UUID targetId, @NotNull @Positive Double amount) {}
