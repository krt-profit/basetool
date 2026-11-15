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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import java.time.Instant;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What a patch change would touch now, counted only from what its live time selects (REQ-PATCH-003,
 * REQ-PATCH-005).
 *
 * @param liveAt the live time the counts are taken at
 * @param counts one entry per kind an enabled effect reports
 * @param wipe what each wipe group and each exception would touch, selected or not
 */
public record PatchChangePreviewDto(
    @NotNull Instant liveAt,
    @NotNull @Unmodifiable List<PatchCountDto> counts,
    @NotNull WipePreviewDto wipe) {}
