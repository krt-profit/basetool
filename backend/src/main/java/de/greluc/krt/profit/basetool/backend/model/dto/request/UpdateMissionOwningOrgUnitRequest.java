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

package de.greluc.krt.profit.basetool.backend.model.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Request DTO for reassigning the owning org unit of a mission (REQ-ORG-018).
 *
 * <p>The target is validated against the caller's assignable scope: a non-admin may pick a unit
 * they belong to or may edit, and {@code null} only when they hold no membership.
 *
 * @param owningOrgUnitId the target org-unit id, or {@code null} for an ownerless mission.
 * @param version the expected {@code Mission.owningOrgUnitVersion} section counter.
 */
public record UpdateMissionOwningOrgUnitRequest(
    @Nullable UUID owningOrgUnitId, @NotNull Long version) {}
