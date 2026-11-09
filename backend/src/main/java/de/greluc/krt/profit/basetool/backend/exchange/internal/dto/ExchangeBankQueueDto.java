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

import java.time.Instant;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One snapshot page of the read-only bank-staff queue (REQ-BXCH-005).
 *
 * @param oldestOpenAt the creation instant of the oldest request in either section, or {@code null}
 *     when both are empty
 * @param oldestOverdue whether that request is 48 hours old or older
 * @param open the web staff queue's pending requests, oldest first
 * @param toApprove the pending requests that wait for the member's approval, oldest first
 * @param nextCursor the cursor of the next page, or {@code null} after the last one
 * @param hasMore whether either section continues
 */
public record ExchangeBankQueueDto(
    @Nullable Instant oldestOpenAt,
    boolean oldestOverdue,
    @NotNull @Unmodifiable List<ExchangeBankQueueItemDto> open,
    @NotNull @Unmodifiable List<ExchangeBankQueueItemDto> toApprove,
    @Nullable String nextCursor,
    boolean hasMore) {}
