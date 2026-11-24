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

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jetbrains.annotations.Nullable;

/**
 * A member's change of an aUEC order's comment through a client (REQ-OXCH-008).
 *
 * @param comment the new comment, plain text; trimmed, and {@code null} or blank clears it
 * @param commentVersion the order's comment version as the client last read it
 */
public record ExchangeJobOrderCommentDto(
    @Nullable @Size(max = JobOrder.COMMENT_MAX_LENGTH) String comment,
    @NotNull @Min(0) Long commentVersion) {}
