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

package de.greluc.krt.profit.basetool.frontend.patchchange.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.time.Instant;

/**
 * The patch change the admin triggers (REQ-PATCH-005), relayed to the backend, which checks the
 * typed confirmation against the label again.
 *
 * @param label the patch label
 * @param kind the patch kind
 * @param liveAt the live time, or {@code null} for the moment of the trigger
 * @param options the options as the admin left them
 * @param confirmation the label as the admin typed it to confirm
 */
@DtoMirror
public record TriggerPatchChangeRequest(
    String label,
    PatchKind kind,
    Instant liveAt,
    PatchChangeOptions options,
    String confirmation) {}
