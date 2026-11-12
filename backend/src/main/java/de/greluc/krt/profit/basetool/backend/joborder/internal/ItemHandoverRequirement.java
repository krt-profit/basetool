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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable current item-line quantities used to prepare a complete handover.
 *
 * @param id the persisted ordered item
 * @param version the current item-line version
 * @param gameItemId the finished item, or null when absent
 * @param amount the requested whole units
 * @param manufactured the manufactured whole units
 * @param delivered the delivered whole units
 */
public record ItemHandoverRequirement(
    @NotNull UUID id,
    long version,
    @Nullable UUID gameItemId,
    int amount,
    int manufactured,
    int delivered) {}
