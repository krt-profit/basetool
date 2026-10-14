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
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Appoints a member as Handelsbeauftragter (REQ-PUR-003).
 *
 * @param userId the member
 * @param validUntil the end of the appointment, or {@code null} for none
 * @param monthlyLimit the personal monthly limit in whole aUEC, or {@code null} for the default
 */
public record AppointDelegateRequest(
    @NotNull UUID userId,
    @Nullable Instant validUntil,
    @Nullable @PositiveOrZero Long monthlyLimit) {}
