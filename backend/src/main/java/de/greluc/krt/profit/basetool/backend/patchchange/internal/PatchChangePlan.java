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
import org.jetbrains.annotations.NotNull;

/**
 * What a patch change's effects are previewed against: the live time that selects what they touch
 * and the chosen options (REQ-PATCH-003, REQ-PATCH-005).
 *
 * @param liveAt the live time; only what was created or started before it is affected
 * @param options the chosen options
 */
public record PatchChangePlan(@NotNull Instant liveAt, @NotNull PatchChangeOptions options) {}
