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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.util.Set;
import java.util.UUID;

/**
 * Inbound request payload for the Update Crew operation.
 *
 * <p>{@code jobTypeIds} is the full replacement set of job types for the crew. {@code version} is
 * the {@code MissionCrew.@Version} the client last saw, echoed back so a stale save is rejected
 * with a 409 rather than silently reverting a concurrent edit of the same crew (#1131). {@code
 * version} is nullable so a legacy caller that omits it skips the check via {@link
 * de.greluc.krt.profit.basetool.backend.support.OptimisticLock#checkOptionalClient}; a present,
 * mismatching value 409s.
 */
public record UpdateCrewRequest(Set<UUID> jobTypeIds, Long version) {}
