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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import java.time.Instant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The runs of one synchronisation or import that finished inside a range, and when it last ran.
 *
 * @param sourceSystem the source's name ({@code UEX}, {@code SCWIKI} or {@code P4K})
 * @param runs how many distinct runs finished inside the range
 * @param lastRanAt when the source last ran at all, inside the range or not, {@code null} when it
 *     never did
 */
public record SyncRunSummaryDto(
    @NotNull String sourceSystem, long runs, @Nullable Instant lastRanAt) {}
