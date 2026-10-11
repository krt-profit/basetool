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

package de.greluc.krt.profit.basetool.backend.operation.internal;

import java.util.UUID;

/**
 * Slim id + name projection of an Operation, used to populate dropdowns and typeaheads without
 * pulling the full {@link OperationDto} payload (description, status, owningSquadron, version).
 * Matches the {@code MissionReferenceDto} contract for the mission picker so both lookup endpoints
 * follow the same shape.
 */
public record OperationReferenceDto(UUID id, String name) {}
