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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request DTO for changing the owner of a mission via the dedicated {@code MissionOwnership}
 * aggregate. The {@code version} must match the current {@code MissionOwnership.version} (NOT the
 * parent {@code Mission.version}) to prevent lost updates on concurrent owner changes.
 */
public record UpdateMissionOwnerRequest(@NotNull UUID userId, @NotNull Long version) {}
