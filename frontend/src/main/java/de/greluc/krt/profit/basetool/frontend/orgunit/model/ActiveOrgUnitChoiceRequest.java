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

package de.greluc.krt.profit.basetool.frontend.orgunit.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A switch of the active org unit relayed to {@code PUT /api/v1/org-units/me/active-choice}
 * (REQ-ORG-031). The switcher sends no version: the latest choice wins.
 *
 * @param orgUnitId the chosen org unit, or {@code null} for all of the caller's org units
 * @param version the version last read, or {@code null} to skip the check
 */
@DtoMirror
public record ActiveOrgUnitChoiceRequest(@Nullable UUID orgUnitId, @Nullable Long version) {}
