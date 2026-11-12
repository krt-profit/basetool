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

/**
 * Immutable persisted demand and quality facts used by a material handover.
 *
 * @param id the demand row identity
 * @param version the demand row version
 * @param materialId the required material
 * @param tierId the selected requirement tier
 * @param tierCode the selected requirement code
 * @param floor the minimum physical quality
 * @param amount the outstanding demand
 */
public record MaterialHandoverRequirement(
    @NotNull UUID id,
    long version,
    @NotNull UUID materialId,
    @NotNull UUID tierId,
    @NotNull String tierCode,
    int floor,
    double amount) {}
