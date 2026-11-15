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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The patch change an effect runs for at the trigger (REQ-PATCH-005): its id, which the affected
 * rows keep as their reason, its label for the areas' summary events, and the plan it was previewed
 * against.
 *
 * @param patchChangeId the patch change being triggered
 * @param label its label
 * @param plan its live time and options
 */
public record PatchChangeRun(
    @NotNull UUID patchChangeId, @NotNull String label, @NotNull PatchChangePlan plan) {}
