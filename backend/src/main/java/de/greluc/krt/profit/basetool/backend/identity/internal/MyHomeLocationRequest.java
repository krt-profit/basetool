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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Body of a member's change of the profile setting "my home location" (REQ-PATCH-030). There is no
 * way back to "no choice": the profile offers only the curated home locations.
 *
 * @param homeLocationId the chosen curated home location
 * @param version the setting's version the caller last read, {@code 0} for a first choice
 */
public record MyHomeLocationRequest(@NotNull UUID homeLocationId, @NotNull Long version) {}
