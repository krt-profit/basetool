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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Write payload of a ship's org-unit change (REQ-HANGAR-009).
 *
 * @param orgUnitId the new owning org unit, one of the owner's direct memberships, or {@code null}
 *     for none
 * @param owningOrgUnitVersion the ship's unit counter the caller last saw; required
 */
public record ShipOrgUnitRequestDto(@Nullable UUID orgUnitId, @NotNull Long owningOrgUnitVersion) {}
