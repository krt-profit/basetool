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
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code AllocationReductionDto} (per the {@code
 * feedback_backend_frontend_dto_mirror} memory): one line of a book-out / transfer "deduct from"
 * plan (Variante C, REQ-INV-027). It takes {@code amount} of the deducted quantity out of the
 * entry's earmark to {@code targetId} (a job order or a mission, depending on which dimension list
 * of {@code InventoryItemBookOutDto} carries it).
 *
 * @param targetId the job order or mission whose slice to shrink; never {@code null}.
 * @param amount the SCU (or whole pieces) to subtract from that slice; strictly positive.
 */
public record AllocationReductionDto(@NotNull UUID targetId, @NotNull @Positive Double amount) {}
