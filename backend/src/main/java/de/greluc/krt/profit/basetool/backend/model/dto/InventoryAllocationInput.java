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
 * One quantity-split assignment supplied at inventory check-in (Variante C, REQ-INV-027, R4):
 * {@code amount} of the newly created entry is earmarked to the job order or mission identified by
 * {@code targetId}. Which dimension it belongs to is given by the list it appears in on {@link
 * InventoryItemCreateDto} ({@code jobOrderAllocations} vs {@code missionAllocations}).
 *
 * <p>The create service enforces, per dimension, that the Σ of these amounts stays within the
 * entry's own amount (R5) and that a personal entry carries none; a job-order target additionally
 * requires the material to be one the order needs.
 *
 * @param targetId the job order / mission to earmark part of the new entry to; never {@code null}.
 * @param amount the SCU to earmark to {@code targetId}; strictly positive.
 */
public record InventoryAllocationInput(@NotNull UUID targetId, @NotNull @Positive Double amount) {}
