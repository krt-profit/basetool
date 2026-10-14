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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.jetbrains.annotations.Nullable;

/**
 * Changes a delegate's personal monthly limit (REQ-PUR-003).
 *
 * @param monthlyLimit the new limit in whole aUEC, or {@code null} to fall back to the default
 * @param version the echoed optimistic-locking version
 */
public record UpdateDelegateLimitRequest(
    @Nullable @PositiveOrZero Long monthlyLimit, @NotNull Long version) {}
