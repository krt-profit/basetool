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

package de.greluc.krt.profit.basetool.backend.supply.internal;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A job order a need of its responsible unit may be linked to (REQ-SUP-003).
 *
 * @param id the job order
 * @param orgUnitId the responsible unit, the only unit whose needs may link the order
 * @param displayId the order's number
 * @param handle the order's short name, or {@code null}
 */
public record NeedJobOrderOptionDto(
    UUID id, UUID orgUnitId, @Nullable Integer displayId, @Nullable String handle) {}
