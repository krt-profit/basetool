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

import de.greluc.krt.profit.basetool.backend.purchasing.api.PauseReason;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Pauses purchasing (REQ-PUR-019).
 *
 * @param orgUnitId the raising unit whose needs may not be bought, or {@code null} for the whole
 *     organisation
 * @param reason why purchasing is paused
 */
public record PausePurchasingRequest(@Nullable UUID orgUnitId, @NotNull PauseReason reason) {}
