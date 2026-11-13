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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.jetbrains.annotations.Nullable;

/**
 * The admin's trigger of a patch change (REQ-PATCH-005).
 *
 * @param label the patch label
 * @param kind the patch kind
 * @param liveAt the live time, or {@code null} for the moment of the trigger
 * @param options the chosen options
 * @param confirmation the label as the admin typed it to confirm; must equal {@code label}
 */
public record TriggerPatchChangeRequest(
    @NotBlank @Size(max = 32) String label,
    @NotNull PatchKind kind,
    @Nullable Instant liveAt,
    @NotNull @Valid PatchChangeOptions options,
    @NotBlank @Size(max = 32) String confirmation) {}
