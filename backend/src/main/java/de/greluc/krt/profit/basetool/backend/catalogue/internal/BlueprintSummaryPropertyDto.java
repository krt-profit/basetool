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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

/**
 * Boundary DTO for one aggregated stat a blueprint affects (the {@code blueprint_summary_property}
 * roll-up). Used to badge a blueprint row with the stats it influences without expanding every
 * slot.
 *
 * @param propertyKey internal stat key (e.g. {@code "weapon_damage"})
 * @param label human-readable stat name (e.g. {@code "Impact Force"})
 * @param betterWhen whether a higher / lower / neutral value is desirable
 */
public record BlueprintSummaryPropertyDto(String propertyKey, String label, String betterWhen) {}
