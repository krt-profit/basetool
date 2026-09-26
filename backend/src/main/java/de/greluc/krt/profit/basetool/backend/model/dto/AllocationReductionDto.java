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
 * One line of a book-out / transfer deduction plan (REQ-INV-027): takes {@code amount} out of the
 * entry's earmark to {@code targetId}, a job order or mission depending on the list it is in. What
 * a dimension's reductions leave uncovered comes from that dimension's unassigned rest.
 *
 * @param targetId the job order or mission whose slice to shrink; never {@code null}
 * @param amount the SCU or pieces to subtract; strictly positive
 */
public record AllocationReductionDto(@NotNull UUID targetId, @NotNull @Positive Double amount) {}
