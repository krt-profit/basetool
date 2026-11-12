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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One recorded change to the requested net amount.
 *
 * @param id the durable amendment identity
 * @param previousTarget the prior exact net target
 * @param target the new exact net target
 * @param recordedAt the recorded amendment time
 * @param actorName the permitted current actor label
 */
public record JobOrderMoneyTargetChangeView(
    UUID id,
    String previousTarget,
    String target,
    Instant recordedAt,
    @Nullable String actorName) {}
