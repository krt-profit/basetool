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

package de.greluc.krt.profit.basetool.frontend.model.dto;

/**
 * Frontend mirror of the backend {@code BlueprintOverviewOwnerDto}: one owner of a blueprint in the
 * overview drill-down, by display name only.
 *
 * @param ownerName the member's effective display name
 * @param orgUnitMember {@code true} when the owner belongs to the caller's oversight org units (or
 *     the admin "all org units" scope); {@code false} when visible only via global sharing
 *     (REQ-INV-018)
 */
public record BlueprintOverviewOwnerDto(String ownerName, boolean orgUnitMember) {}
